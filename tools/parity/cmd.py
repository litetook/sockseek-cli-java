import sys
import os
APP = os.environ.get("SOCKSEEK_PY", r"C:\claude_tasks\Soulseek-Batch")
sys.path.insert(0, APP)
from pathlib import Path
import soulseek as sb
cases = [
    ("defaults", dict(opts=sb.Options())),
    ("tol -1", dict(opts=sb.Options(length_tol=-1))),
    ("preview", dict(opts=sb.Options(length_tol=45, preview=True))),
    ("verbose+conf", dict(opts=sb.Options(length_tol=45), log_file=Path("logs/v.log"),
                          conf=Path("work/sockseek.effective.conf"))),
    ("extra decides", dict(opts=sb.Options(), extra_args=("--no-config", "--no-listen"),
                           conf=Path("work/sockseek.effective.conf"))),
]
for name, kw in cases:
    cmd = sb.build_command(Path("C:/s/sockseek.exe"), Path("work/mix.list.txt"),
                           Path("output/mix"), **kw)
    print(f"== {name}")
    for part in cmd:
        print("   " + part)
