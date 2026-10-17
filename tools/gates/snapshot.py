"""The whole tool over the owner's real playlists, against a recorded answer.

    py tools/gates/snapshot.py              check the three states
    py tools/gates/snapshot.py --record     write down what it prints today

This replaced the parity harness on 2026-10-17. Until then the Java tool was checked by
running the Python tool it was ported from over the same data and diffing every line - the
Python tool was the oracle. That answered one question, "is the port faithful?", and the
answer has been yes since 2026-10-16. Once the two deliberately diverged - one confirmation
instead of N, a live count, a failure record the Python tool never had - every new feature
cost an entry in an ignore list, and the gate was measuring the ignore list rather than the
tool. The owner called it on 2026-10-17: Python is legacy, Java is the tool.

So the oracle is now a FILE: what this tool printed on a day it was known to be right. The
question it answers is different and still worth asking every time - "did anything change
that I did not mean to change?" - and it covers what unit tests cannot: 24 real playlists,
1412 real lines, three states of the output folder, the whole command from the menu down to
the sockseek command line.

When a change is deliberate, re-record and read the diff in the commit. That diff IS the
review: it says in plain text what every playlist will now see.
"""
from __future__ import annotations

import argparse
import difflib
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

import sandbox

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
JAR = REPO / "build" / "libs" / "sockseek-cli.jar"
RECORDED = HERE / "snapshots"
FLAGS = ["--all", "--yes", "--dry-run"]

CLOCK = re.compile(r"^\[\d\d:\d\d:\d\d\]")
# the sandbox lives in a new temporary folder every time, and its name is not the tool's
# behaviour; the shape of the path still is, so only the prefix goes
WHERE = re.compile(r"[A-Za-z]:[\\/][^\s\"]*?sockseek-snap-[^\\/\s\"]+")


def java() -> str:
    home = os.environ.get("JAVA_HOME")
    if home:
        exe = Path(home) / "bin" / ("java.exe" if os.name == "nt" else "java")
        if exe.is_file():
            return str(exe)
    return "java"


def clean(text: str) -> list[str]:
    return [WHERE.sub("<DIR>", CLOCK.sub("[time]", line)) for line in text.splitlines()]


def run(folder: Path) -> list[str]:
    done = subprocess.run([java(), "-Dstdout.encoding=UTF-8", "-jar", str(JAR), *FLAGS],
                          cwd=folder, stdin=subprocess.DEVNULL, capture_output=True, text=True,
                          encoding="utf-8", errors="replace",
                          env={**os.environ, "PYTHONUTF8": "1"}, timeout=900)
    return clean((done.stdout or "") + (done.stderr or "") + f"\n[exit {done.returncode}]")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("only", nargs="*", help="state names; default: all of them")
    parser.add_argument("--record", action="store_true",
                        help="write down today's answer instead of checking against it")
    args = parser.parse_args()

    if not (sandbox.TOOL / "Playlists").is_dir():
        sys.exit(f"no playlists at {sandbox.TOOL} (set SOCKSEEK_HOME)")
    if not JAR.is_file():
        sys.exit(f"{JAR} is missing - run: gradlew fatJar")
    for state in args.only:
        if state not in sandbox.STATES:
            sys.exit(f"unknown state: {state}; known: {', '.join(sandbox.STATES)}")

    RECORDED.mkdir(parents=True, exist_ok=True)
    playlists = len(list((sandbox.TOOL / "Playlists").glob("*.txt")))
    temporary = Path(tempfile.mkdtemp(prefix="sockseek-snap-"))
    worst = 0
    try:
        for state in args.only or list(sandbox.STATES):
            index, files = sandbox.STATES[state]
            said = run(sandbox.build(temporary / state, index, files))
            recorded = RECORDED / f"{state}.txt"
            if args.record:
                recorded.write_text("\n".join(said) + "\n", encoding="utf-8")
                print(f"-- {state}: recorded {len(said)} lines")
                continue
            if not recorded.is_file():
                worst = 1
                print(f"XX {state}: nothing recorded yet - run with --record")
                continue
            before = recorded.read_text(encoding="utf-8").splitlines()
            difference = list(difflib.unified_diff(before, said, "recorded", "now",
                                                   lineterm="", n=1))
            if difference:
                worst = 1
                print(f"XX {state}: CHANGED - {len(difference)} diff line(s)")
                for line in difference[:60]:
                    print("   " + line)
            else:
                print(f"ok {state}: {len(said)} lines unchanged - the whole tool, "
                      f"{playlists} playlists, {' '.join(FLAGS)}")
        return worst
    finally:
        import shutil
        shutil.rmtree(temporary, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
