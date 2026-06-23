package com.kamneko88.comicveil

import android.content.Intent
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.kamneko88.comicveil.ui.home.HomeScreen
import com.kamneko88.comicveil.ui.settings.SettingsScreen
import com.kamneko88.comicveil.ui.theme.ComicVeilTheme
import com.kamneko88.comicveil.ui.transfer.TransferScreen
import com.kamneko88.comicveil.ui.transfer.TransferViewModel
import com.kamneko88.comicveil.ui.viewer.ViewerScreen
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ComicVeilTheme {
                ComicVeilApp(intent = intent)
            }
        }
    }
}

@Composable
fun ComicVeilApp(intent: Intent? = null) {
    val navController = rememberNavController()
    val transferViewModel: TransferViewModel = viewModel()
    val homeViewModel: com.kamneko88.comicveil.ui.home.HomeViewModel = viewModel()

    // 他アプリからの Intentファイルを受け取って直接ビューワーを起動
    LaunchedEffect(intent) {
        if (intent?.action == Intent.ACTION_VIEW) {
            val uri = intent.data ?: return@LaunchedEffect
            val filePath: String? = when (uri.scheme) {
                "file" -> uri.path
                "content" -> {
                    // content URI → アプリキャッシュにコピーしてパスを取得
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
        navController  = navController,
        startDestination = "home"
    ) {
        // ─── ホーム画面 ──────────────────────────────────────────────────
        composable("home") {
            HomeScreen(
                navController     = navController,
                viewModel         = homeViewModel,
                transferViewModel = transferViewModel
            )
        }

        // ─── ビューワー画面 ──────────────────────────────────────────────
        composable(
            route     = "viewer/{filePath}",
            arguments = listOf(
                navArgument("filePath") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val encodedPath = backStackEntry.arguments?.getString("filePath") ?: ""
            val filePath    = URLDecoder.decode(encodedPath, "UTF-8")
            ViewerScreen(
                filePath = filePath,
                onClose  = { navController.popBackStack() }
            )
        }

        // ─── 設定画面 ───────────────────────────────────────────────────────
        composable("settings") {
            SettingsScreen(
                viewModel = homeViewModel,
                onClose   = { navController.popBackStack() }
            )
        }

        // ─── 転送状況画面 ────────────────────────────────────────────────
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