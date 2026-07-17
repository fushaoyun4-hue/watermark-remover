package com.watermarkremover.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.watermarkremover.ui.screens.EditorScreen
import com.watermarkremover.ui.screens.HomeScreen
import com.watermarkremover.ui.screens.ResultScreen

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object Editor : Screen("editor/{mediaUri}?type={type}") {
        fun createRoute(mediaUri: String, type: String = "image") =
            "editor/${java.net.URLEncoder.encode(mediaUri, "UTF-8")}?type=$type"
    }
    data object Result : Screen("result/{originalUri}/{processedUri}?type={type}") {
        fun createRoute(originalUri: String, processedUri: String, type: String = "image") =
            "result/${java.net.URLEncoder.encode(originalUri, "UTF-8")}/${java.net.URLEncoder.encode(processedUri, "UTF-8")}?type=$type"
    }
}

@Composable
fun MainNavigation() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Screen.Home.route
    ) {
        composable(Screen.Home.route) {
            HomeScreen(
                onMediaSelected = { uri, type ->
                    navController.navigate(Screen.Editor.createRoute(uri.toString(), type))
                }
            )
        }

        composable(
            route = Screen.Editor.route,
            arguments = listOf(
                navArgument("mediaUri") { type = NavType.StringType },
                navArgument("type") {
                    type = NavType.StringType
                    defaultValue = "image"
                }
            )
        ) { backStackEntry ->
            val mediaUri = backStackEntry.arguments?.getString("mediaUri")?.let {
                java.net.URLDecoder.decode(it, "UTF-8")
            } ?: ""
            val type = backStackEntry.arguments?.getString("type") ?: "image"

            EditorScreen(
                mediaUri = mediaUri,
                mediaType = type,
                onBack = { navController.popBackStack() },
                onComplete = { originalUri, processedUri ->
                    navController.navigate(
                        Screen.Result.createRoute(originalUri, processedUri, type)
                    ) {
                        popUpTo(Screen.Home.route)
                    }
                }
            )
        }

        composable(
            route = Screen.Result.route,
            arguments = listOf(
                navArgument("originalUri") { type = NavType.StringType },
                navArgument("processedUri") { type = NavType.StringType },
                navArgument("type") {
                    type = NavType.StringType
                    defaultValue = "image"
                }
            )
        ) { backStackEntry ->
            val originalUri = backStackEntry.arguments?.getString("originalUri")?.let {
                java.net.URLDecoder.decode(it, "UTF-8")
            } ?: ""
            val processedUri = backStackEntry.arguments?.getString("processedUri")?.let {
                java.net.URLDecoder.decode(it, "UTF-8")
            } ?: ""
            val type = backStackEntry.arguments?.getString("type") ?: "image"

            ResultScreen(
                originalUri = originalUri,
                processedUri = processedUri,
                mediaType = type,
                onBack = {
                    navController.popBackStack(Screen.Home.route, inclusive = false)
                }
            )
        }
    }
}
