package com.kamneko88.comicveil.ui.lock

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Delete
import com.composables.icons.lucide.FingerprintPattern
import com.composables.icons.lucide.Lock
import com.composables.icons.lucide.Lucide
import com.kamneko88.comicveil.data.AppPrefs

private const val PIN_LENGTH = 4

/** この端末で生体認証（指紋・顔）が使える状態か（設定画面からも利用するためpublic） */
fun isBiometricAvailable(context: Context): Boolean {
    val manager = BiometricManager.from(context)
    val result = manager.canAuthenticate(
        BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK
    )
    return result == BiometricManager.BIOMETRIC_SUCCESS
}

// ─── 共通パーツ：PIN入力状況のドット表示 ─────────────────────────────────

@Composable
private fun PinDots(length: Int, filled: Int, error: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        repeat(length) { i ->
            val filledColor = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(
                        if (i < filled) filledColor else MaterialTheme.colorScheme.outlineVariant
                    )
            )
        }
    }
}

// ─── 共通パーツ：数字キーパッド ────────────────────────────────────────

@Composable
private fun PinKeypad(onDigit: (String) -> Unit, onBackspace: () -> Unit) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("", "0", "⌫")
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        rows.forEach { row ->
            Row {
                row.forEach { key ->
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .padding(6.dp)
                            .clip(CircleShape)
                            .let { base ->
                                if (key.isEmpty()) base else base
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .clickable { if (key == "⌫") onBackspace() else onDigit(key) }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        when {
                            key == "⌫" -> Icon(Lucide.Delete, contentDescription = "1文字削除")
                            key.isNotEmpty() -> Text(key, style = MaterialTheme.typography.headlineSmall)
                        }
                    }
                }
            }
        }
    }
}

// ─── ロック解除画面（アプリ起動時・バックグラウンド復帰時） ────────────────

/**
 * appPrefs.lockMode に応じてPIN入力・生体認証のいずれか（または両方）を表示し、
 * 解除に成功したら onUnlocked を呼ぶ。バックキーで裏の画面には戻れない
 * （NavHostの代わりにこの画面を表示しているだけなので、戻る先が無い＝アプリが閉じるだけ）。
 */
@Composable
fun LockScreen(appPrefs: AppPrefs, onUnlocked: () -> Unit) {
    val context = LocalContext.current
    val mode = appPrefs.lockMode
    val biometricAvailable = remember { isBiometricAvailable(context) }
    val useBiometric = biometricAvailable &&
        (mode == AppPrefs.LockMode.BIOMETRIC_ONLY || mode == AppPrefs.LockMode.BOTH)

    // BOTH＝最初からPIN入力欄を表示。BIOMETRIC_ONLY＝生体認証を前面に出す（PINは「PINを使う」から）
    var showPinPad by remember { mutableStateOf(mode != AppPrefs.LockMode.BIOMETRIC_ONLY) }
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    fun tryUnlockWithPin(candidate: String) {
        if (appPrefs.verifyPin(candidate)) {
            onUnlocked()
        } else {
            error = true
            pin = ""
        }
    }

    fun launchBiometric() {
        val activity = context as? FragmentActivity ?: return
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("ComicVeil")
            .setSubtitle("指紋または顔で解除")
            .setNegativeButtonText("PINを使う")
            .build()
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(context),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onUnlocked()
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // 「PINを使う」タップ・キャンセル・エラー連続などいずれもPIN入力へフォールバック
                    showPinPad = true
                }
                override fun onAuthenticationFailed() {
                    // 指紋・顔の不一致1回のみ。プロンプト自体は継続されるのでここでは何もしない
                }
            }
        )
        prompt.authenticate(promptInfo)
    }

    // 画面表示時に一度だけ自動で生体認証を試みる（PIN_ONLYでは実行されない。
    // BOTHはPIN入力欄を表示しつつ、裏で生体認証プロンプトも同時に出す＝生体認証優先）
    LaunchedEffect(Unit) {
        if (useBiometric) launchBiometric()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp)
        ) {
            Icon(
                imageVector        = Lucide.Lock,
                contentDescription = null,
                modifier           = Modifier.size(40.dp),
                tint               = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(12.dp))
            Text("ComicVeil", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(40.dp))

            if (useBiometric && !showPinPad) {
                Text("指紋または顔で解除してください", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(24.dp))
                OutlinedButton(onClick = { launchBiometric() }) {
                    Icon(Lucide.FingerprintPattern, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("もう一度試す")
                }
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = { showPinPad = true }) { Text("PINを使う") }
            } else {
                PinDots(length = PIN_LENGTH, filled = pin.length, error = error)
                Spacer(Modifier.height(8.dp))
                if (error) {
                    Text(
                        text  = "PINが違います",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    Spacer(Modifier.height(16.dp))
                }
                Spacer(Modifier.height(16.dp))
                PinKeypad(
                    onDigit = { d ->
                        if (pin.length < PIN_LENGTH) {
                            error = false
                            pin += d
                            if (pin.length == PIN_LENGTH) tryUnlockWithPin(pin)
                        }
                    },
                    onBackspace = { if (pin.isNotEmpty()) pin = pin.dropLast(1) }
                )
                if (useBiometric && mode == AppPrefs.LockMode.BIOMETRIC_ONLY) {
                    Spacer(Modifier.height(16.dp))
                    TextButton(onClick = { showPinPad = false; launchBiometric() }) {
                        Text("指紋・顔認証に戻る")
                    }
                }
            }
        }
    }
}

// ─── PIN設定・変更画面（設定画面から遷移） ───────────────────────────────

/**
 * PINを新規設定・変更する画面。2回同じPINを入力させて確認する。
 * activateLockOnSave＝trueなら、保存と同時にロックモードをBOTHにして有効化する
 * （設定画面で「アプリロックを有効にする」を初めてONにしたときの遷移）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PinSetupScreen(
    appPrefs: AppPrefs,
    activateLockOnSave: Boolean,
    onDone: () -> Unit,
    onCancel: () -> Unit
) {
    var firstPin by remember { mutableStateOf<String?>(null) }
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (firstPin == null) "新しいPINを入力" else "もう一度入力して確認") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Lucide.ArrowLeft, contentDescription = "戻る")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier         = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                PinDots(length = PIN_LENGTH, filled = pin.length, error = error)
                Spacer(Modifier.height(8.dp))
                if (error) {
                    Text(
                        text  = "PINが一致しませんでした。最初からやり直してください",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(Modifier.height(24.dp))
                PinKeypad(
                    onDigit = { d ->
                        if (pin.length < PIN_LENGTH) {
                            pin += d
                            if (pin.length == PIN_LENGTH) {
                                val first = firstPin
                                if (first == null) {
                                    // 1回目の入力完了。確認のため2回目の入力へ
                                    firstPin = pin
                                    pin = ""
                                    error = false
                                } else if (pin == first) {
                                    appPrefs.setPin(pin)
                                    if (activateLockOnSave) appPrefs.lockMode = AppPrefs.LockMode.BOTH
                                    onDone()
                                } else {
                                    // 一致しなかったので最初からやり直し
                                    error = true
                                    firstPin = null
                                    pin = ""
                                }
                            }
                        }
                    },
                    onBackspace = { if (pin.isNotEmpty()) pin = pin.dropLast(1) }
                )
            }
        }
    }
}
