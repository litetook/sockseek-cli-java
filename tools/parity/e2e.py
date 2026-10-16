"""The end-to-end gate: both whole tools, over the owner's real playlists, in a sandbox.

The pairs in run.py compare one module at a time. This runs the two tools the way a person
runs them - the menu, the choice, the engine, the report - and diffs every line they print.

A sandbox folder is built to look like the tool's own folder: the Python tool's code, the
config files, the real playlists, and only the `_index.csv` of each export folder. Both
implementations run in the SAME sandbox, one after the other, so the two outputs name the same
paths and the diff needs no normalising; `logs/` and `work/` are wiped in between, so each one
starts at run #0001.

Three states, because they take different paths through the tool:

    fresh        nothing ever downloaded - every track is missing
    index-only   the index says "downloaded" but the files are gone - the WARN path
    on-disk      the index and the files, which is the owner's real state

    py tools/parity/e2e.py                 all three
    py tools/parity/e2e.py on-disk         just that one
    py tools/parity/e2e.py --keep          leave the sandboxes behind to look at

Nothing is downloaded: the run is a dry run, sockseek is a placeholder file that is never
started, and the login in the sandbox is a fake one. The owner's folder is only read, and his
run counter does not move.

Needs: the Python tool checked out (SOCKSEEK_PY), the fat jar (`gradlew fatJar`), and a JDK.
"""
from __future__ import annotations

import argparse
import csv
import difflib
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
PYTHON_TOOL = Path(os.environ.get("SOCKSEEK_PY", r"C:\claude_tasks\Soulseek-Batch"))
JAR = REPO / "build" / "libs" / "sockseek-cli.jar"

# the login never leaves the sandbox, and a dry run never signs in anyway
LOCAL_CONF = "username = parity\npassword = parity\n"
ANSWERS = "all\n"
FLAGS = ["--yes", "--dry-run"]
# written by a run, so they are wiped between the two of them
WRITTEN = ("logs", "work")
# the differences we know about and accept, same list as run.py: the port has no ffprobe,
# and two prompts offer "[b] back", which the Python tool has no counterpart for
IGNORE = ("ffprobe_path", "What to download?", "Paste the ")
# sockseek's own meaning: 1 = downloaded, 3 = was already there and skipped
OK_STATES = {"1", "3"}
# the two runs are a second apart, and the clock is not part of the parity - the SHAPE of the
# stamp still is, so a line that lost its stamp in one of them would still show up as a diff
CLOCK = re.compile(r"^\[\d\d:\d\d:\d\d\]")

# name -> (copy the index files, create a file for every downloaded row)
STATES = {"fresh": (False, False),
          "index-only": (True, False),
          "on-disk": (True, True)}


def java() -> str:
    home = os.environ.get("JAVA_HOME")
    if home:
        exe = Path(home) / "bin" / ("java.exe" if os.name == "nt" else "java")
        if exe.is_file():
            return str(exe)
    return "java"


def sandbox(into: Path, index: bool, files: bool) -> Path:
    """The owner's folder as a run needs it, and nothing more."""
    into.mkdir(parents=True, exist_ok=True)
    shutil.copytree(PYTHON_TOOL / "soulseek", into / "soulseek",
                    ignore=shutil.ignore_patterns("__pycache__"))
    for name in ("run.py", "config.yaml", "sockseek.conf", "dependencies.yaml"):
        shutil.copy2(PYTHON_TOOL / name, into / name)
    (into / "sockseek.local.conf").write_text(LOCAL_CONF, encoding="utf-8")
    # a placeholder: --dry-run prints the command line and starts nothing
    (into / "sockseek.exe").write_bytes(b"")
    shutil.copytree(PYTHON_TOOL / "Playlists", into / "Playlists",
                    ignore=shutil.ignore_patterns("*-missing.txt"))
    if not index:
        return into
    for found in sorted((PYTHON_TOOL / "output").glob("*/_index.csv")):
        target = into / "output" / found.parent.name / found.name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(found, target)
        if files:
            placeholders(target)
    return into


def placeholders(index: Path) -> None:
    """Empty files where the index says a track is already downloaded.

    A row alone does not count as "already here" - both tools insist on the file too - so
    without these every playlist reads as entirely missing. The music itself is never copied:
    it is gigabytes, and nothing here reads a byte of it.
    """
    with index.open(encoding="utf-8-sig", newline="") as handle:
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


def clean(text: str) -> list[str]:
    return [CLOCK.sub("[time]", line) for line in text.splitlines()
            if not any(skip in line for skip in IGNORE)]


def run(command: list[str], folder: Path) -> str:
    done = subprocess.run(command, cwd=folder, input=ANSWERS, capture_output=True, text=True,
                          encoding="utf-8", errors="replace",
                          env={**os.environ, "PYTHONUTF8": "1"}, timeout=900)
    return (done.stdout or "") + (done.stderr or "") + f"\n[exit {done.returncode}]"


def both(state: str, into: Path) -> tuple[list[str], list[str]]:
    """(python lines, java lines) for one state, out of the same folder."""
    index, files = STATES[state]
    folder = sandbox(into, index, files)
    python = run([sys.executable, "-X", "utf8", "run.py", *FLAGS], folder)
    reset(folder)
    java_out = run([java(), "-Dstdout.encoding=UTF-8", "-jar", str(JAR), *FLAGS], folder)
    reset(folder)
    return clean(python), clean(java_out)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("only", nargs="*", help="state names; default: all of them")
    parser.add_argument("--keep", action="store_true", help="do not delete the sandboxes")
    args = parser.parse_args()

    if not (PYTHON_TOOL / "run.py").is_file():
        sys.exit(f"the Python tool is not at {PYTHON_TOOL} (set SOCKSEEK_PY)")
    if not JAR.is_file():
        sys.exit(f"{JAR} is missing - run: gradlew fatJar")
    for state in args.only:
        if state not in STATES:
            sys.exit(f"unknown state: {state}; known: {', '.join(STATES)}")

    playlists = len(list((PYTHON_TOOL / "Playlists").glob("*.txt")))
    temporary = Path(tempfile.mkdtemp(prefix="sockseek-e2e-"))
    worst = 0
    try:
        for state in args.only or list(STATES):
            python, java_out = both(state, temporary / state)
            difference = list(difflib.unified_diff(python, java_out, "python", "java",
                                                   lineterm="", n=1))
            if difference:
                worst = 1
                print(f"XX {state}: DIFFERS - {len(difference)} diff line(s)")
                for line in difference[:40]:
                    print("   " + line)
            else:
                print(f"ok {state}: {len(python)} lines identical - the whole tool, "
                      f"{playlists} playlists, {' '.join(FLAGS)}")
        return worst
    finally:
        if args.keep:
            print(f"-- the sandboxes are at {temporary}")
        else:
            shutil.rmtree(temporary, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
