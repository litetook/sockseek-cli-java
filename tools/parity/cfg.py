import sys, hashlib
import os
APP = os.environ.get("SOCKSEEK_PY", r"C:\claude_tasks\Soulseek-Batch")
sys.path.insert(0, APP)
from pathlib import Path
import soulseek as sb
app = Path(APP)
warnings = []
cfg = sb.load_config(app / "config.yaml", warn=warnings.append)
print("config:", sorted((k, str(v)) for k, v in cfg.items()))
print("warnings:", warnings)
paths = sb.config_paths(cfg, app)
for k in sorted(paths):
    print("path", k, "=", paths[k])
work = Path(sys.argv[1])
merged = sb.effective_conf(app, work, warnings.append)
text = merged.read_text(encoding="utf-8")
print("keys:", sorted(sb.parse_conf(text)))
print("sha256:", hashlib.sha256(text.encode("utf-8")).hexdigest())
print("min-bitrate:", sb.read_min_bitrate(merged))
print("missing credentials:", sb.missing_credentials(text))
