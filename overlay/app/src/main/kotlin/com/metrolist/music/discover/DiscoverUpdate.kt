/**
 * Metrolist Discover (fork addition) - GPL-3.0
 */
package com.metrolist.music.discover

/**
 * Where the in-app updater looks (wired in by the 3rd hook, in utils/Updater.kt).
 * Points at THIS fork's GitHub releases so the updater can never offer the original Metrolist APK.
 * The release title is the comparable version (e.g. 13.7.0.5) and the asset is named Metrolist.apk,
 * which is what Updater.kt expects for the universal "foss" build.
 * While the repo is private, the API answers 404 -> Updater's runCatching fails -> no update is shown (silent).
 */
object DiscoverUpdate {
    const val REPO = "oz-lev/metrolist-discover"
    const val API_BASE = "https://api.github.com/repos/$REPO"
    /** The upstream KMP check is redirected to a release tag that never exists, so it never offers anything. */
    const val KMP_URL = "$API_BASE/releases/tags/no-kmp-build"
    const val KMP_APK_NAME = "no-kmp-build.apk"
}
