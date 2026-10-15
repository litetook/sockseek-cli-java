import sys
import os
APP = os.environ.get("SOCKSEEK_PY", r"C:\claude_tasks\Soulseek-Batch")
sys.path.insert(0, APP)
from pathlib import Path
import soulseek as sb
tool = Path(APP.replace(chr(92), "/") + "/yt-dlp.exe")
reader = sb.LinkReader()
for url, t in (("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M", None),
               ("https://soundcloud.com/bonobo/sets/outlier-radio-on-nts", tool)):
    linked = reader.read(url, t, None)
    print(f"== {linked.source} name={linked.name!r} {len(linked.lines)} lines")
    for line in linked.lines:
        print("  " + line)
    for warning in linked.warnings:
        print("  WARN " + warning)
