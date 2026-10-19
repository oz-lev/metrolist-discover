#!/usr/bin/env python3
"""Inserts the two Metrolist-Discover hooks into an upstream Metrolist checkout (GPL-3.0).

Anchor based instead of line-number patches, so upstream edits around the hooks don't break it.
Idempotent (a hook already present is left alone). Exits 3 with a clear message when an anchor
cannot be found - that is the "upstream changed, a human must look" signal the sync bot reports.
"""
import re, sys, pathlib

UP = pathlib.Path(sys.argv[1])
MARK = "[metrolist-discover hook]"
errors = []

def edit(rel, fn):
    p = UP / rel
    if not p.exists():
        errors.append(f"{rel}: file not found"); return
    src = p.read_text()
    if MARK in src:
        print(f"{rel}: hook already present"); return
    out = fn(src)
    if out is None:
        errors.append(f"{rel}: anchor not found"); return
    p.write_text(out)
    print(f"{rel}: hook inserted")

# Hook 1 - MusicService.onPlaybackStatsReady: report every playback session (incl. short skips).
def music_service(src):
    m = re.search(r"override fun onPlaybackStatsReady\(", src)
    if not m: return None
    n = re.compile(r"^([ \t]*)val mediaItem = .*$", re.M).search(src, m.end())
    if not n or n.start() - m.end() > 600: return None
    indent = n.group(1)
    line = (f"\n{indent}runCatching {{ com.metrolist.music.discover.DiscoverHooks.onPlaybackStats(this, database, mediaItem, "
            f"playbackStats.totalPlayTimeMs, dataStore.get(PauseListenHistoryKey, false)) }} // {MARK}")
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
    hook = f"\n\n{indent}com.metrolist.music.discover.ui.discoverForYouSection(this) // {MARK}"
    return src[:i + 1] + hook + src[i + 1:]

# Hook 3 - utils/Updater.kt: the in-app updater reads THIS fork's releases, never upstream's (or the KMP app's).
def updater(src):
    reps = [
        (r'private const val GITHUB_API_BASE = "[^"]*"', "private const val GITHUB_API_BASE = com.metrolist.music.discover.DiscoverUpdate.API_BASE"),
        (r'private const val KMP_LATEST_RELEASE_URL = "[^"]*"', "private const val KMP_LATEST_RELEASE_URL = com.metrolist.music.discover.DiscoverUpdate.KMP_URL"),
        (r'private const val KMP_APK_NAME = "[^"]*"', "private const val KMP_APK_NAME = com.metrolist.music.discover.DiscoverUpdate.KMP_APK_NAME"),
    ]
    for pat, rep in reps:
        src, n = re.subn(pat, rep + f" // {MARK}", src, count=1)
        if n != 1 and "KMP" not in pat: return None
    if "MetrolistGroup/Metrolist" in src:
        return None  # some other upstream release URL appeared: refuse rather than silently offer upstream APKs
    return src

edit("app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt", music_service)
edit("app/src/main/kotlin/com/metrolist/music/ui/screens/HomeScreen.kt", home_screen)
edit("app/src/main/kotlin/com/metrolist/music/utils/Updater.kt", updater)
if errors:
    for e in errors: print("::error::" + e)
    sys.exit(3)
