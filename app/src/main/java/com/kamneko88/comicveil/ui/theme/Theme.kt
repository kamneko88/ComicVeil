package com.kamneko88.comicveil.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80
)

private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40
)

/**
 * darkTheme＝trueで固定のダークテーマ、falseで固定の白系テーマを適用する
 * （端末のライト/ダーク設定には連動しない。ユーザーが設定画面で選ぶ）。
 * Android 12以上は端末のダイナミックカラー（壁紙由来の配色）を優先し、
 * それ以外の端末では上記の固定配色にフォールバックする。
 */
@Composable
fun ComicVeilTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (darkTheme) dynamicDarkColorScheme(LocalContext.current)
        else dynamicLightColorScheme(LocalContext.current)
    } else {
        if (darkTheme) DarkColorScheme else LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
