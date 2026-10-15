import sys
import os
APP = os.environ.get("SOCKSEEK_PY", r"C:\claude_tasks\Soulseek-Batch")
sys.path.insert(0, APP)
from pathlib import Path
import soulseek as sb
cases = [[], ["--only", "mix"], ["--retries", "3", "--length-tol", "-1", "--dry-run"],
         ["--playlists", "lists", "--no-explain", "--verbose"]]
for argv in cases:
    cfg = sb.load_config(sb.APP_DIR / "config.yaml", warn=lambda m: None)
    args = sb.parse_args(argv, cfg)
    print("== " + " ".join(argv))
    for line in sb.describe_config(args):
        print(line)
