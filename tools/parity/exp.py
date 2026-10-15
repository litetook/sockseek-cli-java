import sys
import os
APP = os.environ.get("SOCKSEEK_PY", r"C:\claude_tasks\Soulseek-Batch")
sys.path.insert(0, APP)
from pathlib import Path
import soulseek as sb
app = Path(APP)
out = app / "output/cry-for-you-linds-edit-top30"
playlist = sb.Playlist.from_file(app / "Playlists/cry-for-you-linds-edit-top30.txt")
rows = sb.read_index(sb.find_index_files(out))
failures = [f for f in sb.failure_rows(playlist.entries, rows) if f['failurereason'] == '4']
work = Path(sys.argv[1]); work.mkdir(parents=True, exist_ok=True)
list_file = work / "cry-for-you-linds-edit-top30.list.txt"
list_file.write_text("\n".join(e.list_line for e in playlist.entries) + "\n", encoding="utf-8")
conf = sb.effective_conf(app, work)
client = sb.Sockseek(exe=app / "sockseek.exe", cwd=app, opts=sb.Options(length_tol=45),
                     timeout_min=10, retries=1, conf=conf)
found = sb.explain_failures(failures, playlist.entries, list_file, client, out, lambda m: print("LOG", m))
by_line = {e.original: e for e in playlist.entries}
for failure in failures:
    print(sb.format_failure(failure))
    entry = by_line.get(failure["playlist_line"])
    key = entry.track.title if entry else None
    for line in sb.explain_failure(failure, found.get(key), 45, sb.read_min_bitrate(conf)):
        print(line)
