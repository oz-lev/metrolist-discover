#!/usr/bin/env python3
"""Metrolist backup (.backup/.zip) or song.db  ->  TSV files the evaluator reads (GPL-3.0).

usage: python3 tools/backup_to_tsv.py <Metrolist_xxx.backup | song.db> <out_dir>

Everything stays local. Do not commit the output: it is personal listening data.
Reads only stable columns of Metrolist's Room schema (event, song, song_artist_map, artist,
playlist_song_map, related_song_map).
"""
import os, sqlite3, sys, tempfile, zipfile

src, out = sys.argv[1], sys.argv[2]
os.makedirs(out, exist_ok=True)
tmp = None
if zipfile.is_zipfile(src):
    tmp = tempfile.mkdtemp()
    with zipfile.ZipFile(src) as z:
        for n in z.namelist():
            if n.startswith("song.db"):
                z.extract(n, tmp)
    src = os.path.join(tmp, "song.db")
con = sqlite3.connect(src)
q = con.execute
def clean(s):
    return (s or "").replace("\t", " ").replace("\n", " ")

artists = {}
for sid, aid, name, pos in q("select m.songId, m.artistId, a.name, m.position from song_artist_map m join artist a on a.id=m.artistId order by m.songId, m.position"):
    artists.setdefault(sid, []).append((aid, clean(name)))
with open(os.path.join(out, "tracks.tsv"), "w") as f:
    for sid, title, dur, album in q("select id, title, duration, albumId from song"):
        a = artists.get(sid, [])
        f.write("\t".join([sid, clean(title), str(dur or -1), album or "", ";".join(x[0] for x in a), ";".join(x[1] for x in a)]) + "\n")
with open(os.path.join(out, "plays.tsv"), "w") as f:
    for sid, ts, pt in q("select songId, timestamp, playTime from event order by timestamp"):
        f.write(f"{sid}\t{ts}\t{pt}\n")
with open(os.path.join(out, "signals.tsv"), "w") as f:
    for sid, ld in q("select id, likedDate from song where liked=1"):
        f.write(f"{sid}\tLIKE\t{ld or ''}\n")
    for sid, d in q("select id, dateDownload from song where dateDownload is not null or isDownloaded=1"):
        f.write(f"{sid}\tDOWNLOAD\t{d or ''}\n")
    for sid, d in q("select id, inLibrary from song where inLibrary is not null"):
        f.write(f"{sid}\tLIBRARY_ADD\t{d or ''}\n")
    for (sid,) in q("select distinct songId from playlist_song_map"):
        f.write(f"{sid}\tPLAYLIST_ADD\t\n")
with open(os.path.join(out, "related.tsv"), "w") as f:
    for s, r in q("select songId, relatedSongId from related_song_map order by id"):
        f.write(f"{s}\t{r}\n")
print("wrote", out)
