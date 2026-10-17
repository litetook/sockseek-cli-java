"""A folder shaped like a real installation, built somewhere else.

No gate ever runs inside one. Reading a file there is one thing; starting a download, moving
a run counter or rewriting an index is another, and a check that can do either is a check
nobody will dare run twice. So every gate copies what it needs - the playlists, the index
files, the config - into a temporary folder and works there.

Three states, because a playlist behaves differently depending on what is already on disk:

    fresh       nothing downloaded yet: everything is missing
    index-only  sockseek's index is there but the audio files are not: everything is missing
                again, and the tool has to notice that an index row without a file is a lie
    on-disk     index AND files: most of it is already here, little or nothing to search
"""
from __future__ import annotations

import csv
import os
import shutil
from pathlib import Path

FIXTURE = Path(__file__).resolve().parent / "fixture"
# Only mock.py needs the real sockseek binary, which is 114 MB and has no business in a
# repository. Point SOCKSEEK_HOME at any folder that has one - an installation of this tool
# is the obvious candidate - and the gate copies it out rather than running anything there.
TOOL = Path(os.environ.get("SOCKSEEK_HOME", str(Path.cwd())))
# the login never leaves the sandbox, and a dry run never signs in anyway
LOCAL_CONF = "username = gate\npassword = gate\n"
# sockseek's own meaning: 1 = downloaded, 3 = was already there and skipped
OK_STATES = {"1", "3"}
# name -> (copy the index files, create a file for every downloaded row)
STATES = {"fresh": (False, False),
          "index-only": (True, False),
          "on-disk": (True, True)}
# written by a run, so they are wiped between runs of the same sandbox
WRITTEN = ("logs", "work")


def build(into: Path, index: bool, files: bool) -> Path:
    into.mkdir(parents=True, exist_ok=True)
    for name in ("config.yaml", "sockseek.conf", "dependencies.yaml"):
        shutil.copy2(FIXTURE / name, into / name)
    (into / "sockseek.local.conf").write_text(LOCAL_CONF, encoding="utf-8")
    # a placeholder: --dry-run prints the command line and starts nothing
    (into / "sockseek.exe").write_bytes(b"")
    shutil.copytree(FIXTURE / "Playlists", into / "Playlists",
                    ignore=shutil.ignore_patterns("*-missing.txt"), dirs_exist_ok=True)
    if not index:
        return into
    for found in sorted((FIXTURE / "output").glob("*/_index.csv")):
        target = into / "output" / found.parent.name / found.name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(found, target)
        if files:
            placeholders(target)
    return into


def placeholders(index: Path) -> None:
    """An empty file wherever the index claims one arrived, so "here" means something."""
    with index.open(encoding="utf-8", newline="") as handle:
        for row in csv.DictReader(handle):
            if (row.get("state") or "").strip() not in OK_STATES:
                continue
            given = (row.get("filepath") or "").strip()
            if not given:
                continue
            file = (index.parent / given).resolve()
            if index.parent.resolve() not in file.parents:
                continue   # a row pointing outside its own folder is not ours to create
            file.parent.mkdir(parents=True, exist_ok=True)
            file.touch(exist_ok=True)


def reset(folder: Path) -> None:
    for name in WRITTEN:
        shutil.rmtree(folder / name, ignore_errors=True)
