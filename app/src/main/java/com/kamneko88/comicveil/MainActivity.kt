package com.kamneko88.comicveil

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.kamneko88.comicveil.data.AppPrefs
import com.kamneko88.comicveil.ui.backup.BackupScreen
import com.kamneko88.comicveil.ui.home.HomeScreen
import com.kamneko88.comicveil.ui.history.HistoryScreen
import com.kamneko88.comicveil.ui.lock.AppLockState
import com.kamneko88.comicveil.ui.lock.LockScreen
import com.kamneko88.comicveil.ui.lock.PinSetupScreen
import com.kamneko88.comicveil.ui.settings.SettingsScreen
import com.kamneko88.comicveil.ui.theme.ComicVeilTheme
import com.kamneko88.comicveil.ui.transfer.TransferScreen
import com.kamneko88.comicveil.ui.transfer.TransferViewModel
import com.kamneko88.comicveil.ui.viewer.ViewerScreen
import com.kamneko88.comicveil.ui.volumes.ArchiveVolumeScreen
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * アプリロック（BiometricPrompt）を使うため FragmentActivity が必要
 * （FragmentActivity は ComponentActivity を継承しているため、Compose の setContent 等はそのまま使える）
 */
class MainActivity : FragmentActivity() {

    // 音量キーイベントを ViewerScreen に転送するコールバック
    // ViewerScreen が DisposableEffect で登録・解除する
    var volumeKeyListener: ((keyCode: Int) -> Boolean)? = null

    // 転送の進捗通知を表示するための許可リクエスト（Android 13以降で必要）
    // 拒否されても転送自体は継続できるため、結果は特に扱わない
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()
        setContent {
            val context = LocalContext.current
            val appPrefs = remember { AppPrefs(context) }
            var appTheme by remember { mutableStateOf(appPrefs.appTheme) }
            val isDark = appTheme == AppPrefs.AppTheme.DARK

            // システムバーのアイコン色をテーマに合わせて切り替える。
            // ダーク：背景が暗いのでアイコンは白（dark style）。
            // 白系：背景が明るいのでアイコンは黒（light style）。
            // enableEdgeToEdge() は何度でも呼び直せるため、テーマが変わるたびに再適用する。
            LaunchedEffect(isDark) {
                if (isDark) {
                    enableEdgeToEdge(
                        statusBarStyle     = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
                        navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    )
                } else {
                    enableEdgeToEdge(
                        statusBarStyle     = SystemBarStyle.light(
                            android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT
                        ),
                        navigationBarStyle = SystemBarStyle.light(
                            android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT
                        )
                    )
                }
            }

            ComicVeilTheme(darkTheme = isDark) {
                ComicVeilApp(
                    intent        = intent,
                    onThemeChange = { newTheme ->
                        appTheme = newTheme
                        appPrefs.appTheme = newTheme
                    }
                )
            }
        }
    }

    /** Android 13以降で通知許可を求める（未許可の場合のみ） */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // 登録済みリスナーが音量キーを消費したら super を呼ばない
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            val consumed = volumeKeyListener?.invoke(keyCode) ?: false
            if (consumed) return true
        }
        return super.onKeyDown(keyCode, event)
    }
}

@Composable
fun ComicVeilApp(
    intent: Intent? = null,
    onThemeChange: (AppPrefs.AppTheme) -> Unit = {}
) {
    val navController = rememberNavController()
    val transferViewModel: TransferViewModel = viewModel()
    val homeViewModel: com.kamneko88.comicveil.ui.home.HomeViewModel = viewModel()

    // ─── アプリロック ───────────────────────────────────────────────────
    // バックグラウンドに一度でも行ったら再ロックする。ProcessLifecycleOwnerで
    // アプリ全体（個々のActivityではなく）のフォアグラウンド/バックグラウンドを監視する。
    val lockContext = LocalContext.current
    val appPrefs = remember { AppPrefs(lockContext) }
    var locked by remember {
        mutableStateOf(appPrefs.lockMode != AppPrefs.LockMode.OFF && !AppLockState.isUnlocked)
    }
    DisposableEffect(Unit) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && appPrefs.lockMode != AppPrefs.LockMode.OFF) {
                AppLockState.isUnlocked = false
                locked = true
            }
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(observer)
        onDispose { ProcessLifecycleOwner.get().lifecycle.removeObserver(observer) }
    }

    if (locked) {
        LockScreen(
            appPrefs   = appPrefs,
            onUnlocked = {
                AppLockState.isUnlocked = true
                locked = false
            }
        )
        return
    }

    // 他アプリからの Intentファイルを受け取って直接ビューワーを起動
    LaunchedEffect(intent) {
        if (intent?.action == Intent.ACTION_VIEW) {
            val uri = intent.data ?: return@LaunchedEffect
            val filePath: String? = when (uri.scheme) {
                "file" -> uri.path
                "content" -> {
                    val context = navController.context
                    val fileName = context.contentResolver.query(
                        uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
                    )?.use { cursor ->
                        if (cursor.moveToFirst())
                            cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                        else null
                    } ?: "shared_file"
                    val destFile = File(context.cacheDir, "shared_$fileName")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        destFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    destFile.absolutePath
                }
                else -> null
            }
            if (filePath != null) {
                val encoded = URLEncoder.encode(filePath, "UTF-8")
                navController.navigate("viewer/$encoded")
            }
        }
    }

    NavHost(
        navController    = navController,
        startDestination = "home"
    ) {
        composable("home") {
            HomeScreen(
                navController     = navController,
                viewModel         = homeViewModel,
                transferViewModel = transferViewModel
            )
        }

        composable(
            route     = "viewer/{filePath}",
            arguments = listOf(
                navArgument("filePath") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val encodedPath = backStackEntry.arguments?.getString("filePath") ?: ""
            val filePath    = URLDecoder.decode(encodedPath, "UTF-8")
            // ビューワー（実際に読む画面）はアプリ全体のテーマ設定に関わらず常にダーク固定
            ComicVeilTheme(darkTheme = true) {
                ViewerScreen(
                    filePath       = filePath,
                    onClose        = { navController.popBackStack() },
                    onOpenSettings = { navController.navigate("settings") }
                )
            }
        }

        composable(
            route     = "volumes/{archivePath}",
            arguments = listOf(
                navArgument("archivePath") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val encodedPath = backStackEntry.arguments?.getString("archivePath") ?: ""
            val archivePath = URLDecoder.decode(encodedPath, "UTF-8")
            ArchiveVolumeScreen(
                archivePath   = archivePath,
                navController = navController,
                onClose       = { navController.popBackStack() }
            )
        }

        composable("settings") {
            SettingsScreen(
                viewModel              = homeViewModel,
                onClose                = { navController.popBackStack() },
                onNavigateToLockSetup  = { activate -> navController.navigate("lock_setup/$activate") },
                onThemeChange          = onThemeChange
            )
        }

        composable(
            route     = "lock_setup/{activate}",
            arguments = listOf(navArgument("activate") { type = NavType.BoolType })
        ) { backStackEntry ->
            val activate = backStackEntry.arguments?.getBoolean("activate") ?: false
            PinSetupScreen(
                appPrefs           = appPrefs,
                activateLockOnSave = activate,
                onDone             = { navController.popBackStack() },
                onCancel           = { navController.popBackStack() }
            )
        }

        composable("history") {
            HistoryScreen(navController = navController)
        }

        composable("backup") {
            BackupScreen(onClose = { navController.popBackStack() })
        }

        composable(
            route     = "transfer/{folderName}",
            arguments = listOf(
                navArgument("folderName") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val encoded    = backStackEntry.arguments?.getString("folderName") ?: ""
            val folderName = URLDecoder.decode(encoded, "UTF-8")
                .let { if (it == "home") null else it.ifEmpty { null } }
            TransferScreen(
                viewModel      = transferViewModel,
                fromFolderName = folderName,
                onClose        = { navController.popBackStack() }
            )
        }
    }
}
