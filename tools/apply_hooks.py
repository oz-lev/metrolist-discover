#!/usr/bin/env python3
"""Inserts the Metrolist-Discover hooks (9 of them, see README-OZ.md) into an upstream Metrolist checkout (GPL-3.0).

Anchor based instead of line-number patches, so upstream edits around the hooks don't break it.
Idempotent (a hook already present is left alone). Exits 3 with a clear message when an anchor
cannot be found - that is the "upstream changed, a human must look" signal the sync bot reports.
"""
import re, sys, pathlib

UP = pathlib.Path(sys.argv[1])
MARK = "[metrolist-discover hook]"
errors = []

def edit(rel, fn, name):
    """Apply one hook. Every hook line carries "[metrolist-discover hook] <name>" so a file can hold several."""
    p = UP / rel
    if not p.exists():
        errors.append(f"{rel}: file not found ({name})"); return
    src = p.read_text()
    if f"{MARK} {name}" in src:
        print(f"{rel}: hook '{name}' already present"); return
    out = fn(src)
    if out is None or out == src:
        errors.append(f"{rel}: anchor for hook '{name}' not found"); return
    p.write_text(out)
    print(f"{rel}: hook '{name}' inserted")

# Hook 1 - MusicService.onPlaybackStatsReady: report every playback session (incl. short skips).
def music_service(src):
    m = re.search(r"override fun onPlaybackStatsReady\(", src)
    if not m: return None
    n = re.compile(r"^([ \t]*)val mediaItem = .*$", re.M).search(src, m.end())
    if not n or n.start() - m.end() > 600: return None
    indent = n.group(1)
    line = (f"\n{indent}runCatching {{ com.metrolist.music.discover.DiscoverHooks.onPlaybackStats(this, database, mediaItem, "
            f"playbackStats.totalPlayTimeMs, dataStore.get(PauseListenHistoryKey, false)) }} // {MARK} stats")
    return src[:n.end()] + line + src[n.end():]

# Hook 2 - HomeScreen: the "Discover for you" section right under the chips row.
def home_screen(src):
    m = re.search(r"\bfun HomeScreen\(", src)
    if not m: return None
    lc = src.find("LazyColumn(", m.end())
    chips = src.find("ChipsRow(", lc)
    if lc < 0 or chips < 0: return None
    item = src.rfind("item {", lc, chips)
    if item < 0: return None
    depth = 0; i = src.index("{", item)
    while i < len(src):
        if src[i] == "{": depth += 1
        elif src[i] == "}":
            depth -= 1
            if depth == 0: break
        i += 1
    else:
        return None
    line_start = src.rfind("\n", 0, item) + 1
    indent = src[line_start:item]
    hook = f"\n\n{indent}com.metrolist.music.discover.ui.discoverForYouSection(this) // {MARK} home"
    return src[:i + 1] + hook + src[i + 1:]

# Hook 3 - utils/Updater.kt: the in-app updater reads THIS fork's releases, never upstream's (or the KMP app's).
def updater(src):
    reps = [
        (r'private const val GITHUB_API_BASE = "[^"]*"', "private const val GITHUB_API_BASE = com.metrolist.music.discover.DiscoverUpdate.API_BASE"),
        (r'private const val KMP_LATEST_RELEASE_URL = "[^"]*"', "private const val KMP_LATEST_RELEASE_URL = com.metrolist.music.discover.DiscoverUpdate.KMP_URL"),
        (r'private const val KMP_APK_NAME = "[^"]*"', "private const val KMP_APK_NAME = com.metrolist.music.discover.DiscoverUpdate.KMP_APK_NAME"),
    ]
    for pat, rep in reps:
        src, n = re.subn(pat, rep + f" // {MARK} updater", src, count=1)
        if n != 1 and "KMP" not in pat: return None
    if "MetrolistGroup/Metrolist" in src:
        return None  # some other upstream release URL appeared: refuse rather than silently offer upstream APKs
    return src

# Hook 4 - NavigationBuilder: the v2 screens (Mixes hub, Search lab, Weird settings, Profile) as "discover/*" routes.
def navigation(src):
    m = re.search(r"fun NavGraphBuilder\.navigationBuilder\(", src)
    if not m: return None
    body = re.compile(r"\)\s*\{[ \t]*\n").search(src, m.end())
    if not body or body.start() - m.end() > 600: return None
    line = f"    com.metrolist.music.discover.v2.ui.discoverRoutes(this, navController) // {MARK} routes\n"
    return src[:body.end()] + line + src[body.end():]

# Hook 5 - Player.kt (portrait layout): vibe buttons + animated art-colour strip right under the controls.
def player(src):
    pat = re.compile(r"(mediaMetadata\?\.let \{\s*controlsContent\(it\)\s*\}\s*\n)([ \t]*)(Spacer\(Modifier\.height\(30\.dp\)\))")
    found = pat.findall(src)
    if len(found) != 1: return None
    return pat.sub(lambda m: m.group(1) + m.group(2) + f"com.metrolist.music.discover.v2.ui.NowPlayingVibeBar() // {MARK} player\n\n" + m.group(2) + m.group(3), src, count=1)

# Hook 6 - MusicService.onCreate: queue guard (weird-settings rules) + neighbours mode + overnight-mix scheduling.
def service(src):
    m = re.search(r"override fun onCreate\(\)", src)
    if not m: return None
    n = re.compile(r"^([ \t]*)playerInitialized\.value = true[ \t]*$", re.M).search(src, m.end())
    if not n or n.start() - m.end() > 8000: return None
    line = f"\n{n.group(1)}runCatching {{ com.metrolist.music.discover.v2.V2Service.attach(this) }} // {MARK} service"
    return src[:n.end()] + line + src[n.end():]

# Hook 7 (v2.1) - ui/theme/Theme.kt: the design system. The final MaterialTheme(...) inside MetrolistTheme becomes
# DiscoverTheme(...), which re-themes the WHOLE app (colours, Hebrew font, shapes, motion) from the Discover settings.
def theme(src):
    m = re.search(r"fun MetrolistTheme\(", src)
    if not m: return None
    pat = re.compile(r"MaterialTheme\(\s*colorScheme = colorScheme,\s*content = content,?\s*\)")
    found = pat.findall(src, m.end())
    if len(found) != 1: return None
    n = pat.search(src, m.end())
    rep = f"com.metrolist.music.discover.v21.theme.DiscoverTheme(colorScheme, darkTheme, themeColor, content) // {MARK} theme"
    return src[:n.start()] + rep + src[n.end():]

# Hook 8 (v2.1) - ui/component/AppNavigation.kt: the bottom navigation bar gets the Discover look (rounded top, light line).
def navbar(src):
    m = re.search(r"fun AppNavigationBar\(", src)
    if not m: return None
    pat = re.compile(r"NavigationBar\(\s*\n([ \t]*)modifier = modifier,")
    n = pat.search(src, m.end())
    if not n or n.start() - m.end() > 1500: return None
    rep = f"NavigationBar(\n{n.group(1)}modifier = com.metrolist.music.discover.v21.theme.DiscoverChrome.navBar(modifier), // {MARK} navbar"
    return src[:n.start()] + rep + src[n.end():]

# Hook 9 (v2.1) - ui/player/MiniPlayer.kt (new design): gradient glow border instead of the plain outline.
def miniplayer(src):
    anchor = ".border(1.dp, outlineColor.copy(alpha = 0.3f), RoundedCornerShape(32.dp))"
    if src.count(anchor) != 1: return None
    return src.replace(anchor, f".then(com.metrolist.music.discover.v21.theme.DiscoverChrome.miniBorder(outlineColor)) // {MARK} miniplayer", 1)

edit("app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt", music_service, "stats")
edit("app/src/main/kotlin/com/metrolist/music/ui/screens/HomeScreen.kt", home_screen, "home")
edit("app/src/main/kotlin/com/metrolist/music/utils/Updater.kt", updater, "updater")
edit("app/src/main/kotlin/com/metrolist/music/ui/screens/NavigationBuilder.kt", navigation, "routes")
edit("app/src/main/kotlin/com/metrolist/music/ui/player/Player.kt", player, "player")
edit("app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt", service, "service")
edit("app/src/main/kotlin/com/metrolist/music/ui/theme/Theme.kt", theme, "theme")
edit("app/src/main/kotlin/com/metrolist/music/ui/component/AppNavigation.kt", navbar, "navbar")
edit("app/src/main/kotlin/com/metrolist/music/ui/player/MiniPlayer.kt", miniplayer, "miniplayer")
if errors:
    for e in errors: print("::error::" + e)
    sys.exit(3)
