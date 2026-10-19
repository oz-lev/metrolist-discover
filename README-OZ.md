# Metrolist Discover — מדריך קצר

**מה זה:** Metrolist הרגילה + "גילוי בשבילך": מנוע המלצות שלומד ממה שאתה שומע, ישירות בטלפון. בלי שרת, בלי לשלוח נתונים לשום מקום.

## התקנה
1. נכנסים ל-**Releases** בריפו ומורידים את `Metrolist-Discover.apk`.
2. מתקינים. האפליקציה נקראת **Metrolist Discover** ומותקנת **ליד** Metrolist המקורית — לא צריך למחוק כלום.
3. להעביר את ההיסטוריה: ב-Metrolist המקורית ← הגדרות ← גיבוי ושחזור ← גיבוי. ב-Metrolist Discover ← הגדרות ← גיבוי ושחזור ← שחזור ← בוחרים את הקובץ. האפליקציה תופעל מחדש עם כל ההיסטוריה, הלייקים והפלייליסטים.

## מה יש בפנים
- **גילוי בשבילך** — בראש מסך הבית: 30 שירים, בערך 70% "בטוח" ו-30% גילוי. אף פעם לא אותו אמן פעמיים ברצף, ובלי שירים ששמעת ממש לאחרונה. ⟳ מרענן, ✦ רדיו חכם מנגן הכל.
- **המיקסים שלך** — המיקס היומי, שכחת שאהבת, מיקס לפי שעה ביום, ו"בסגנון…" לכל עולם מוזיקלי שלך. מתרעננים לבד כל כמה שעות.
- **בועת סטטיסטיקה** — בכל פתיחה: עובדה אחת עליך (מחושבת מההיסטוריה). לחיצה = פופ, החלקה = עובדה הבאה. אפשר לכבות.
- **⚙ (כפתור הכיוונון ליד הכותרת)** — כמה מההמלצות שמעת עד הסוף / עשית לייק / דילגת, כמה פעמים המנוע כבר כיוונן את עצמו, "איפוס למידה", ו"בדיקה על ההיסטוריה שלי".

## איך זה משתפר לבד
כל שיר מהגילוי ששומעים עד הסוף, עושים לו לייק או מדלגים עליו — מזיז קצת את משקלות הדירוג (בגבולות בטוחים), ואת אחוז הגילוי (בין 15% ל-45%).

## עדכונים
בוט בגיטהאב בודק כל בוקר אם יצאה גרסה חדשה של Metrolist. אם כן — הוא בונה, חותם ומפרסם APK חדש ב-Releases. אם משהו נשבר, נפתח Issue בריפו והגרסה הקודמת נשארת.
העדכון האוטומטי של האפליקציה (הגדרות ← עדכונים) מסתכל על הריפו **הזה** בלבד — אף פעם לא יציע את Metrolist המקורית. כשיש גרסה חדשה מופיעה הודעה; לוחצים, מורידים ומתקינים מעל.

---

# Technical notes (English)

## Layout
```
UPSTREAM_TAG                         last upstream Metrolist release the fork is built on (bot-maintained)
overlay/                             NEW files copied into a clean upstream checkout - never edits upstream files
  app/src/main/kotlin/com/metrolist/music/discover/
    engine/                          pure Kotlin engine (no Android imports): profile, candidates, ranking,
                                     diversity, self-tuner, evaluator, stats facts, mixes, synthetic world
    DiscoverHooks.kt                 the single entry point upstream code calls (playback sessions)
    DiscoverStore.kt                 own SQLite db "metrolist_discover.db" (plays incl. <30 s skips, recs, kv)
    DiscoverRepository.kt            reads Metrolist's Room tables read-only + innertube calls -> engine
    ui/DiscoverHomeSection.kt        home row, mixes, stats bubble (Popup), settings bottom sheet
    ui/DiscoverViewModel.kt          @HiltViewModel
  app/src/main/res/{values,values-iw}/discover_strings.xml, drawable/discover_ic_*.xml
  app/src/test/...                   JUnit tests + discover/baseline.properties (evaluator gate)
tools/apply-overlay.sh               copy overlay + insert hooks (fails if upstream ever ships a same-named file)
tools/apply_hooks.py                 anchor-based, idempotent hook insertion; exit 3 = anchor moved upstream
tools/eval-cli/EvalCli.kt            offline evaluator CLI (same engine code)
tools/backup_to_tsv.py               Metrolist backup/song.db -> TSV for the CLI (local only, never commit output)
.github/actions/{build-apk,engine-check}, .github/workflows/{build.yml,sync.yml}
```

## Hook points (the entire upstream footprint: 2 lines, 0 Gradle changes)
1. `app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt`, in `onPlaybackStatsReady(...)`, right after `val mediaItem = ...`:
   `runCatching { com.metrolist.music.discover.DiscoverHooks.onPlaybackStats(this, database, mediaItem, playbackStats.totalPlayTimeMs, dataStore.get(PauseListenHistoryKey, false)) }`
   (respects "pause listen history"; Metrolist itself only stores sessions >= 30 s, the hook also records the short ones = skips).
2. `app/src/main/kotlin/com/metrolist/music/ui/screens/HomeScreen.kt`, inside the home `LazyColumn`, right after the `item { ChipsRow(...) }` block:
   `com.metrolist.music.discover.ui.discoverForYouSection(this)`

3. `app/src/main/kotlin/com/metrolist/music/utils/Updater.kt`: the three constants `GITHUB_API_BASE`, `KMP_LATEST_RELEASE_URL`, `KMP_APK_NAME`
   are pointed at `com.metrolist.music.discover.DiscoverUpdate` (this repo's releases; the KMP check goes to a tag that never exists).
   `apply_hooks.py` refuses to build if any `MetrolistGroup/Metrolist` URL remains in Updater.kt, so the fork can never offer the upstream APK.

All hook lines carry the marker `[metrolist-discover hook]`. App id / name use upstream's own env overrides
(`METROLIST_APPLICATION_ID=com.metrolist.music.oz`, `METROLIST_APP_NAME="Metrolist Discover"`), versionName gets `+<commit>`
via upstream's `METROLIST_BUILD_COMMIT`. Flavor: **foss** (upstream's own updater, re-pointed; no Cast). No permission is added.

## Versioning / in-app updates
`ci/discover.init.gradle` (passed with `-I`, no upstream file touched) sets versionCode = upstream versionCode × 10000 + bot build number
(the sync workflow's run number; plain CI builds use 0) and versionName / `BuildConfig.BASE_VERSION_NAME` = `<upstream version>.<build>`.
Releases are titled exactly that comparable version (e.g. `13.7.0.5`, tag `v13.7.0.5`) and carry the APK twice: `Metrolist-Discover.apk`
and `Metrolist.apk` (the asset name upstream's updater picks for the universal foss build). The updater compares the release title with
`BASE_VERSION_NAME` and opens the asset's download URL. This needs the repo to be public (unauthenticated GitHub API); on a private repo the
API answers 404, `Updater.checkForUpdate()` fails inside its `runCatching`, and the app simply shows no update.

## Engine
- Signals: Metrolist `event` (songId, timestamp, playTime) + own short-session log; `song.liked/likedDate`, `dateDownload`,
  `inLibrary`, `playlist_song_map`; `related_song_map` as an offline candidate source.
  Full listen +1, partial +0.35·ratio, early skip −0.9, repeat within 24 h +1.5, like +4, playlist/download +2.5, library +1.5.
- Thresholds come from the user's own distribution: skip fraction = p20 of completion ratios (clamped 0.12–0.35),
  skip seconds = p10 of played seconds (15–45 s), full = p50 ratio (0.6–0.9), "recent" = p25 of repeat gaps, novelty scale = median repeat gap, forgotten = 3× that.
- Profiles: artist/album/track affinities, exponential decay, short (half-life 4 d) and long (60 d), blended 0.6/0.4; log-scaled against the user's own median/max.
  Hour-of-day × weekday/weekend histograms per artist (Fri–Sun treated as weekend).
- Candidates: YouTube Music radio (`YouTube.next` RDAMVM) for the top 4 seeds; artist pages of the top 3 artists (top songs + related artists' songs);
  forgotten favorites; related_song_map; other songs of top artists known locally; exploration (2-hop related + a mid-ranked artist's related artist).
- Score = wSim·similarity + wTime·hourFit + wNov·novelty − wSkip·skip − wOver·over-representation + wSrc·sourcePrior (`RankWeights`, all knobs in `DiscoverConfig`).
- List: ~70/30 exploit/explore (explore share bandit-tuned 15–45 %), no same artist twice in a row, dedupe vs the user's recent window, drop tracks skipped a lot.
- Self-tuning: online gradient step per played recommendation (reward +1 full, +0.3 partial, −1 skip, +1.5 like), learning rate decays, weights bounded to [0.25×, 3×] defaults; reset in the sheet.

## Evaluation
- `Evaluator.holdout`: hide the last N days, train on the rest, recall/precision/day-hit @K for NEW songs and ALL songs, vs most-popular, Metrolist plain related, random.
- CI: `engine-check` compiles the engine with kotlinc, runs JUnit tests and `EvalCli --synthetic --baseline ... --check` (fails if any engine metric drops > 0.02 below the stored baseline or the engine stops beating random/related); the Gradle build also runs the same tests inside the app.
- Real data, locally: `python3 tools/backup_to_tsv.py Metrolist_xxx.backup /tmp/me && kotlinc overlay/.../engine/*.kt tools/eval-cli/EvalCli.kt -include-runtime -d eval.jar && java -cp eval.jar EvalCliKt --tsv /tmp/me --top 20`
  (or on the phone: Discover sheet -> "Test on my history"). Personal data never goes to the repo or CI.
- Updating the baseline after an intentional improvement: `java -cp eval.jar EvalCliKt --synthetic --write-baseline overlay/app/src/test/resources/discover/baseline.properties`.

## Sync bot (`sync.yml`)
Daily 03:17 UTC + manual (`force_release`). Reads the latest upstream release; if it differs from `UPSTREAM_TAG`: engine gate -> clean upstream tag + overlay + hooks -> Gradle unit tests + `assembleIzzyRelease` -> zipalign + apksigner with the repo's own key -> GitHub release `discover-<tag>-r<run>` -> commit the new `UPSTREAM_TAG`.
Any failure (hook anchor gone, compile error, test/metric regression) fails the run and opens/updates an issue labelled `sync-failure`.
This is "rebase by re-application": instead of merging upstream history into this repo, every build starts from the pristine upstream tag, so there are no merge conflicts to resolve - only the two anchors can break, and the bot says so.

## Backup / restore compatibility
Metrolist's backup is a zip with fixed entry names (`settings.preferences_pb`, `song.db`, `-wal`, `-shm`, artist aliases) written/read via
`context.filesDir` / `getDatabasePath` of whichever app runs it (`BackupRestoreViewModel`); nothing in it depends on the package name, and the
fork uses the same Room schema (version 38 at v13.7.0) because it never changes upstream tables. Oz's real backup (Room v38) was read by the
evaluator pipeline end to end. Restore inside the fork on a real phone: not yet observed.

## Signing
New key made for this fork only (RSA 4096, alias `metrolistdiscover`), stored as Actions secrets `KEYSTORE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
