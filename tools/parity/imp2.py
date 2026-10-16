"""Importing: a generated m3u and a Spotify-shaped csv, built from a real playlist.

The csv deliberately carries commas inside quoted fields and the titles carry umlauts: that is
where two different CSV parsers would part company.
"""
import csv
import os
import sys
from pathlib import Path

APP = Path(os.environ.get("SOCKSEEK_PY", r"C:\claude_tasks\Soulseek-Batch"))
sys.path.insert(0, str(APP))
import soulseek as sb

work = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("work")
exports = work.parent / "exports"
exports.mkdir(parents=True, exist_ok=True)
playlist = sb.Playlist.from_file(APP / "Playlists" / "melodic-ambient-playlist.txt")

with (exports / "spotify.csv").open("w", encoding="utf-8", newline="") as fh:
    writer = csv.writer(fh)
    writer.writerow(["Track Name", "Artist Name(s)", "Album Name", "Duration (ms)"])
    for entry in playlist.entries:
        name = entry.track.title
        artist, _, title = name.partition(" - ")
        writer.writerow([title or name, artist, "Album, With Comma",
                         (entry.track.length or 0) * 1000])

with (exports / "player.m3u").open("w", encoding="utf-8", newline="\n") as fh:
    fh.write("#EXTM3U\n")
    for entry in playlist.entries:
        fh.write("#EXTINF:" + str(entry.track.length or -1) + "," + entry.track.title + "\n")
        fh.write("C:\\music\\" + entry.track.title + ".flac\n")

# Malformed on purpose, and not invented: this is what Exportify wrote in the owner's own
# Liked Songs export (2026-10-16) - quotes inside a quoted field that are not doubled. The
# Python csv module reads it, commons-csv refuses it, and the Java tool used to die with an
# UncheckedIOException instead of importing anything. The playlist lines must come out the
# same; the extra WARN the Java side prints about it is a known, accepted difference.
(exports / "broken-quotes.csv").write_text(
    """Track Name,Artist Name(s),Duration (ms),Record Label
Fortepiano,Khrystyna Soloviy,246760,"TOV "Superior Music""
Second Song,Someone Else,120000,Plain Label
""",
    encoding="utf-8", newline="")

for file in sorted(exports.iterdir()):
    found = sb.Importer().read(file)
    print(f"== {file.name} [{found.source}] {len(found.lines)} lines")
    for line in found.lines:
        print("  " + line)
    for warning in found.warnings:
        print("  WARN " + warning)
