import sys
import os
APP = os.environ.get("SOCKSEEK_PY", r"C:\claude_tasks\Soulseek-Batch")
sys.path.insert(0, APP)
from pathlib import Path
import soulseek as sb
for file in sb.Playlist.find(Path(APP.replace(chr(92), "/") + "/Playlists")):
    playlist = sb.Playlist.from_file(file)
    print("==", playlist.name)
    for entry in playlist.entries:
        print(entry.list_line)
    for warning in playlist.warnings:
        print("WARN", warning)
