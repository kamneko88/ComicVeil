package com.kamneko88.comicveil

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.kamneko88.comicveil.ui.home.HomeScreen
import com.kamneko88.comicveil.ui.theme.ComicVeilTheme
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
    NavHost(
        navController = navController,
        startDestination = "home"
    ) {
        composable("home") {
            HomeScreen(navController = navController)
        }
        composable(
            route = "viewer/{filePath}",
            arguments = listOf(
                navArgument("filePath") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val encodedPath = backStackEntry.arguments?.getString("filePath") ?: ""
            val filePath = URLDecoder.decode(encodedPath, "UTF-8")
            ViewerScreen(
                filePath = filePath,
                onClose = { navController.popBackStack() }
            )
        }
    }
}