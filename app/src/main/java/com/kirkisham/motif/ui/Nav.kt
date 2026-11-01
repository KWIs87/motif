package com.kirkisham.motif.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.kirkisham.motif.MotifApp
import com.kirkisham.motif.ui.home.HomeScreen

/** Route names. Screens are added here as the build order reaches them (CLAUDE.md). */
object Routes {
    const val HOME = "home"
    const val ALL = "all"
    const val PLAYLIST = "playlist/{id}"
    const val NOW = "now"
    const val SETTINGS = "settings"
    const val ALARM = "alarm"
    fun playlist(id: String) = "playlist/$id"
}

@Composable
fun MotifNavHost(app: MotifApp, nav: NavHostController = rememberNavController()) {
    NavHost(nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                app = app,
                onOpenPlaylist = { /* Stage 2 */ },
                onOpenNowPlaying = { /* Stage 2 */ },
            )
        }
    }
}
