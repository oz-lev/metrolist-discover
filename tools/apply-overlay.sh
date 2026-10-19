#!/usr/bin/env bash
# Applies the Metrolist-Discover overlay onto a clean upstream Metrolist checkout.
# usage: tools/apply-overlay.sh <path-to-upstream-checkout>
# 1) copies every file under overlay/ (new files only: they live in their own package
#    com.metrolist.music.discover, so they never collide with upstream files)
# 2) inserts the two one-line hooks with tools/apply_hooks.py (anchor based, idempotent,
#    exits non-zero with a clear message when an anchor moved upstream).
set -euo pipefail
FORK="$(cd "$(dirname "$0")/.." && pwd)"
UP="$(cd "$1" && pwd)"
echo "overlay -> $UP"
while IFS= read -r -d '' f; do
  rel="${f#"$FORK/overlay/"}"
  if [ -e "$UP/$rel" ] && ! cmp -s "$f" "$UP/$rel"; then
    echo "::error::upstream now ships $rel, which the overlay also provides"; exit 4
  fi
done < <(find "$FORK/overlay" -type f -print0)
cp -R "$FORK/overlay/." "$UP/"
python3 "$FORK/tools/apply_hooks.py" "$UP"
echo "overlay applied"
