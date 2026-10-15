import sys
import os
APP = os.environ.get("SOCKSEEK_PY", r"C:\claude_tasks\Soulseek-Batch")
sys.path.insert(0, APP)
from pathlib import Path
import soulseek as sb
root = Path(APP)
for file in sb.Playlist.find(root / "Playlists"):
    playlist = sb.Playlist.from_file(file)
    out = root / "output" / playlist.name
    status = sb.playlist_status(playlist.entries, out)
    print(f"== {playlist.name}: {len(status.have)} here, {len(status.missing)} missing")
    for entry in status.have:
        print("  HAVE", entry.original, "->", status.files[entry.original].name)
    rows = sb.read_index(sb.find_index_files(out))
    for failure in sb.failure_rows(playlist.entries, rows):
        print("  " + sb.format_failure(failure))
