import sys
import os
APP = os.environ.get("SOCKSEEK_PY", r"C:\claude_tasks\Soulseek-Batch")
sys.path.insert(0, APP)
from pathlib import Path
import soulseek as sb
for file in sorted(Path(APP.replace(chr(92), "/") + "/Playlists").glob("*.txt")):
    found = sb.Importer().read(file)
    print(f"== {file.name} [{found.source}] {len(found.lines)} lines")
    for line in found.lines:
        print("  " + line)
    for warning in found.warnings:
        print("  WARN " + warning)
