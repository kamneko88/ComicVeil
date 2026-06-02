package com.kamneko88.comicveil

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
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
import java.net.URLDecoder
import java.net.URLEncoder

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ComicVeilTheme {
                ComicVeilApp()
            }
        }
    }
}

@Composable
fun ComicVeilApp() {
    val navController = rememberNavController()

    // TransferViewModel をアプリ全体で1つ共有
    val transferViewModel: TransferViewModel = viewModel()

    // HomeViewModel をアプリ全体で1つ共有（設定画面と共有するため）
    val homeViewModel: com.kamneko88.comicveil.ui.home.HomeViewModel = viewModel()

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