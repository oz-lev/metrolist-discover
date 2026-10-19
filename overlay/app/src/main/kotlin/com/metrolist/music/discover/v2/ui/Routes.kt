/**
 * Metrolist Discover v2 (fork addition) - GPL-3.0
 */
package com.metrolist.music.discover.v2.ui

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable

/**
 * The v2 screens, added to Metrolist's NavHost by hook 4 (one line at the top of navigationBuilder()).
 * Routes are prefixed "discover/" so they can never collide with upstream routes.
 */
object V2Routes {
    const val MIXES = "discover/mixes"
    const val SEARCH = "discover/search"
    const val WEIRD = "discover/weird"
    const val PROFILE = "discover/profile"
    // v2.1
    const val THEME = "discover/theme"
    const val PROFILES = "discover/profiles"
    const val KARAOKE = "discover/karaoke"
    const val CAR = "discover/car"
    const val SAMPLES = "discover/samples"
    const val QUEUES = "discover/queues"
    const val BLEND = "discover/blend"
    const val REPLAY = "discover/replay"
}

fun discoverRoutes(builder: NavGraphBuilder, navController: NavHostController) {
    builder.composable(V2Routes.MIXES) { MixesHubScreen(onBack = { navController.navigateUp() }) }
    builder.composable(V2Routes.SEARCH) { SearchLabScreen(onBack = { navController.navigateUp() }, onRecognize = { navController.navigate("recognition") }) }
    builder.composable(V2Routes.WEIRD) { com.metrolist.music.discover.v21.ui.SettingsHubScreen(onBack = { navController.navigateUp() }, onNavigate = { navController.navigate(it) }) }
    builder.composable(V2Routes.PROFILE) { ProfileScreen(onBack = { navController.navigateUp() }) }
    builder.composable(V2Routes.THEME) { com.metrolist.music.discover.v21.ui.ThemeStudioScreen(onBack = { navController.navigateUp() }) }
    builder.composable(V2Routes.PROFILES) { com.metrolist.music.discover.v21.ui.ProfilesScreen(onBack = { navController.navigateUp() }) }
    builder.composable(V2Routes.KARAOKE) { com.metrolist.music.discover.v21.ui.KaraokeScreen(onBack = { navController.navigateUp() }) }
    builder.composable(V2Routes.CAR) { com.metrolist.music.discover.v21.ui.CarModeScreen(onBack = { navController.navigateUp() }) }
    builder.composable(V2Routes.SAMPLES) { com.metrolist.music.discover.v21.ui.SamplesScreen(onBack = { navController.navigateUp() }) }
    builder.composable(V2Routes.QUEUES) { com.metrolist.music.discover.v21.ui.QueuesScreen(onBack = { navController.navigateUp() }) }
    builder.composable(V2Routes.BLEND) { com.metrolist.music.discover.v21.ui.BlendScreen(onBack = { navController.navigateUp() }) }
    builder.composable(V2Routes.REPLAY) { com.metrolist.music.discover.v21.ui.ReplayScreen(onBack = { navController.navigateUp() }) }
}
