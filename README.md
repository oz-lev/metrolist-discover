# Metrolist Discover (private fork)

Metrolist (YouTube Music client, GPL-3.0) + an on-device "Discover for you" engine, auto mixes and a stats bubble.
The upstream app is never edited by hand: CI takes a clean upstream release, copies `overlay/` in and inserts two one-line hooks
(`tools/apply_hooks.py`). See **README-OZ.md** (Hebrew guide + technical notes).
