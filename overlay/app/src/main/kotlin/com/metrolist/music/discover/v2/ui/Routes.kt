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
}

fun discoverRoutes(builder: NavGraphBuilder, navController: NavHostController) {
    builder.composable(V2Routes.MIXES) { MixesHubScreen(onBack = { navController.navigateUp() }) }
    builder.composable(V2Routes.SEARCH) { SearchLabScreen(onBack = { navController.navigateUp() }, onRecognize = { navController.navigate("recognition") }) }
    builder.composable(V2Routes.WEIRD) { WeirdSettingsScreen(onBack = { navController.navigateUp() }) }
    builder.composable(V2Routes.PROFILE) { ProfileScreen(onBack = { navController.navigateUp() }) }
}
