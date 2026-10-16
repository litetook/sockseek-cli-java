"""Run both implementations over the same real data and diff what they printed.

This is the gate the port is judged by. A unit test checks what I thought of; this checks
what I did not - three real bugs were found by it and by nothing else (a whole number printed
as 45.0, a U+FFFD from yt-dlp, a NO-BREAK SPACE from Spotify).

    py tools/parity/run.py                 every pair
    py tools/parity/run.py playlist index  only those

Each pair is two scripts of the same name: <name>.py reads the Python tool, <name>.jsh reads
this one through jshell. Both print the same shape, and the only thing that counts is that
their output is identical.

Needs: the Python tool checked out, this project built (`gradlew classes`), and a JDK.
"""
from __future__ import annotations

import argparse
import difflib
import os
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
PYTHON_TOOL = Path(os.environ.get("SOCKSEEK_PY", r"C:\claude_tasks\Soulseek-Batch"))

# name -> what it compares, and whether it needs the network
PAIRS = {
    "dump": ("playlist files -> sockseek lines", False),
    "idx": ("_index.csv -> what is here, what failed", False),
    "cfg": ("config.yaml and the three sockseek layers (compares a sha256, never a value)", False),
    "imp": ("importing the real playlists as plain text", False),
    "imp2": ("importing a generated m3u and a Spotify-shaped csv", False),
    "cmd": ("the generated sockseek command lines", False),
    "blk": ("the configuration block the run log opens with", False),
    "ui": ("the front end: the menu, the missing lists, what the engine is handed",
           False),
    "lnk": ("a Spotify playlist and a SoundCloud set", True),
    "exp": ("the explain step against live Soulseek", True),
}
# the one difference we know about and accept: the Java port has no ffprobe yet
# the Java side warns about a malformed CSV quote where Python silently tolerates it
# (imp2, the broken-quotes.csv case): the lines still have to match, the warning may differ
IGNORE = ("ffprobe_path", "is not doubled")


def java_classpath() -> str:
    classes = REPO / "build" / "classes" / "java" / "main"
    jars = sorted((REPO / "build" / "libs").glob("*.jar"))
    if not classes.is_dir():
        sys.exit("build/classes is missing - run: gradlew classes")
    return str(classes) + (os.pathsep + str(jars[0]) if jars else "")


def jshell() -> str:
    home = os.environ.get("JAVA_HOME")
    if home:
        exe = Path(home) / "bin" / ("jshell.exe" if os.name == "nt" else "jshell")
        if exe.is_file():
            return str(exe)
    return "jshell"


def run(command: list[str], env: dict | None = None) -> str:
    done = subprocess.run(command, capture_output=True, text=True, encoding="utf-8",
                          errors="replace", env=env, cwd=REPO, timeout=900)
    return (done.stdout or "") + (done.stderr or "")


def both(name: str, work: Path) -> tuple[str, str]:
    """(python output, java output) for one pair, each given its own work folder."""
    environment = {**os.environ, "PYTHONUTF8": "1"}
    python = run([sys.executable, str(HERE / f"{name}.py"), str(work / "py")], environment)
    java = run([jshell(), "-R-Dstdout.encoding=UTF-8", f"-R-Dworkdir={work / 'java'}",
                f"-R-Dexports={work / 'exports'}", "--class-path", java_classpath(),
                "-q", str(HERE / f"{name}.jsh")], environment)
    return python, java


def clean(text: str) -> list[str]:
    return [line for line in text.splitlines()
            if not any(skip in line for skip in IGNORE)]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("only", nargs="*", help="pair names; default: all of them")
    parser.add_argument("--offline", action="store_true", help="skip the pairs that need Soulseek")
    args = parser.parse_args()

    if not PYTHON_TOOL.is_dir():
        sys.exit(f"the Python tool is not at {PYTHON_TOOL} (set SOCKSEEK_PY)")
    names = args.only or list(PAIRS)
    worst = 0
    with tempfile.TemporaryDirectory() as temporary:
        work = Path(temporary)
        for name in names:
            if name not in PAIRS:
                sys.exit(f"unknown pair: {name}; known: {', '.join(PAIRS)}")
            what, needs_network = PAIRS[name]
            if needs_network and args.offline:
                print(f"-- {name}: skipped (needs the network)")
                continue
            python, java = both(name, work)
            difference = list(difflib.unified_diff(clean(python), clean(java),
                                                   "python", "java", lineterm="", n=1))
            if difference:
                worst = 1
                print(f"XX {name}: DIFFERS - {what}")
                for line in difference[:40]:
                    print("   " + line)
            else:
                print(f"ok {name}: {len(clean(python))} lines identical - {what}")
    return worst


if __name__ == "__main__":
    sys.exit(main())
