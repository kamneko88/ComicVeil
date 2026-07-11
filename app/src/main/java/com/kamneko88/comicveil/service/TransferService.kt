package com.kamneko88.comicveil.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.kamneko88.comicveil.MainActivity
import com.kamneko88.comicveil.data.nas.TransferItem
import com.kamneko88.comicveil.data.nas.TransferManager
import com.kamneko88.comicveil.data.nas.TransferStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * ダウンロードを継続させるフォアグラウンドサービス
 *
 * 【役割】
 * - 通知バーに進捗を表示しながら転送を継続する
 * - 「重要な処理を実行中」とOSに宣言し、他アプリ使用中でもプロセスがkillされにくくする
 * - WakeLock / WifiLock を保持し、画面オフ・スリープ中もSMB接続が切れないようにする
 * - キューが空になったら自動的に自身を終了する
 *
 * 転送処理そのものは TransferManager が持つ。このサービスは「転送を守る器」の役割。
 */
class TransferService : Service() {

    companion object {
        private const val TAG = "ComicVeil"

        private const val CHANNEL_ID   = "comicveil_transfer"
        private const val CHANNEL_NAME = "ファイル転送"
        private const val NOTIF_ID     = 1001

        /** 通知の「全てキャンセル」ボタンから送られるアクション */
        const val ACTION_CANCEL_ALL = "com.kamneko88.comicveil.action.CANCEL_ALL"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        TransferManager.init(this)
        createChannel()

        // 5秒以内にstartForegroundを呼ぶ必要があるため、まず暫定の通知で前面化する
        startForegroundCompat(buildNotification(TransferManager.currentItem))
        acquireLocks()

        // 転送状態を監視して通知を更新。キューが空になったら自身を終了する
        scope.launch {
            TransferManager.items.collect { items ->
                val active = items.firstOrNull { it.status == TransferStatus.TRANSFERRING }
                    ?: items.firstOrNull { it.status == TransferStatus.WAITING }

                if (active == null) {
                    Log.d(TAG, "転送キューが空になったため転送サービスを終了します")
                    stopSelf()
                } else {
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.notify(NOTIF_ID, buildNotification(active))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL_ALL) {
            Log.d(TAG, "通知から全キャンセルが要求されました")
            TransferManager.cancelAll()
        }
        // 強制終了された場合、キューの内容までは復元できないので再起動はしない
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releaseLocks()
        scope.cancel()
        super.onDestroy()
    }

    // ─── 通知 ────────────────────────────────────────────────────────────

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW  // 音を鳴らさない（進捗表示のみ）
        ).apply {
            description = "ファイル転送の進捗を表示します"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(item: TransferItem?): Notification {
        // 通知タップでアプリを開く
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )

        // 「全てキャンセル」アクション
        val cancelIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, TransferService::class.java).apply { action = ACTION_CANCEL_ALL },
            PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(item?.fileName ?: "ファイルを転送中")
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent)
            .addAction(0, "全てキャンセル", cancelIntent)

        val fraction = item?.fraction
        if (fraction != null) {
            val percent = (fraction * 100).toInt().coerceIn(0, 100)
            builder.setContentText("$percent%  ${item.progressText}")
            builder.setProgress(100, percent, false)
        } else {
            builder.setContentText(item?.progressText ?: "準備中")
            builder.setProgress(0, 0, true)  // サイズ不明時は不定プログレス
        }

        return builder.build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    // ─── ロック（スリープ中も転送を継続させる） ─────────────────────────

    private fun acquireLocks() {
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "ComicVeil::TransferWakeLock"
            ).apply { acquire(3 * 60 * 60 * 1000L) }  // 最大3時間の保険付き

            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "ComicVeil::TransferWifiLock"
            ).apply { acquire() }

            Log.d(TAG, "WakeLock / WifiLock を取得しました")
        }.onFailure {
            Log.w(TAG, "ロックの取得に失敗: ${it.message}")
        }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        wifiLock = null
        Log.d(TAG, "WakeLock / WifiLock を解放しました")
    }
}
