"""The phase-6 gate: the manuals and the tools say the same thing.

A manual that documents an option the tool does not accept, or a tool that accepts an option
no manual mentions, is a bug of the same kind as a wrong line of output: this text is the
interface of the tool. It was written after the Python manual was found to still describe a
`FAILED n/m` summary line that neither implementation prints (2026-10-15).

What it compares, for each implementation:

    documented  every --option named in its README and its docs/README.md
    accepted    every --option in its own --help and in its engine's --help

    py tools/parity/docs.py          both implementations
    py tools/parity/docs.py java     just this one

Options that belong to sockseek, yt-dlp or Gradle are named in the manuals on purpose and are
listed in FOREIGN below; the two implementations' own options are compared against each other
as well, with the differences we mean to have in EXPECTED.

The Python tool is asked for its help inside a SANDBOX COPY, never in the folder the owner
works in: a run there is his to approve, even one that only prints a help text (his rule of
2026-10-16). The manuals are read from his folder, because reading is not running.

Needs: the Python tool checked out (SOCKSEEK_PY), the fat jar (`gradlew fatJar`), and a JDK.
"""
from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

import e2e   # its sandbox(), so nothing here runs inside the owner's own folder

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
PYTHON_TOOL = Path(os.environ.get("SOCKSEEK_PY", r"C:\claude_tasks\Soulseek-Batch"))
JAR = REPO / "build" / "libs" / "sockseek-cli.jar"

OPTION = re.compile(r"--[a-z][a-z0-9]*(?:-[a-z0-9]+)*")
ANSI = re.compile(r"\x1b\[[0-9;]*m")
# An option is one a help text LISTS, not one it merely mentions: both argparse and picocli
# print it at the start of its own line, or inside the brackets of the usage line, while
# examples like `--sockseek-args="--desperate"` sit in the middle of a sentence. Reading every
# token as an option made the first version of this gate report --desperate as a flag of the
# tool, which it is not.
LISTED = re.compile(r"(?:^\s+|[\[(]\s*)(?:-[A-Za-z],\s+)?(--[a-z][a-z0-9]*(?:-[a-z0-9]+)*)",
                    re.MULTILINE)

# not ours: they are named in the manuals because that is where you pass them
FOREIGN = {
    # sockseek's own options (section 7, section 8 and the command the tool builds)
    "--artist-maybe-wrong", "--concurrent-searches", "--desperate", "--extract-artist",
    "--index-path", "--input-type", "--max-bitrate", "--max-retries", "--max-stale-time",
    "--min-bitrate", "--mock-files-dir", "--name-format", "--no-config", "--no-skip-existing",
    "--output-dir", "--pref-min-bitrate", "--print", "--remove-brackets", "--remove-ft",
    "--searches-per-time", "--searches-renew-time", "--search-timeout", "--strict-conditions",
    "--pref-max-bitrate", "--pref-format", "--format", "--max-bitrate",
    "--strict-title",
    # yt-dlp, for the SoundCloud import (section 1a)
    "--flat-playlist", "--ignore-errors", "--no-warnings", "--skip-download",
    # the build and the test runner (section 10, section 11)
    "--offline", "--tests",
    # sockseek again: the tool adds this one itself when --verbose is given
    "--log-file",
    # setup.py's own flags, and setup.py is a program of the Python tool, not of its engine
    "--all", "--check", "--upgrade",
}
# the differences between the two implementations that are meant to be there
EXPECTED = {"--ffprobe-check", "--ffprobe"}


def java() -> str:
    home = os.environ.get("JAVA_HOME")
    if home:
        exe = Path(home) / "bin" / ("java.exe" if os.name == "nt" else "java")
        if exe.is_file():
            return str(exe)
    return "java"


def ask(command: list[str], where: Path) -> str:
    done = subprocess.run(command, cwd=where, capture_output=True, text=True, encoding="utf-8",
                          errors="replace", env={**os.environ, "PYTHONUTF8": "1"}, timeout=300)
    return ANSI.sub("", (done.stdout or "") + (done.stderr or ""))


def options(text: str) -> set[str]:
    return set(OPTION.findall(text))


def documented(files: list[Path]) -> set[str]:
    found: set[str] = set()
    for file in files:
        if not file.is_file():
            sys.exit(f"no such file: {file}")
        found |= options(file.read_text(encoding="utf-8"))
    return found


def accepted(helps: list[str]) -> set[str]:
    found: set[str] = set()
    for text in helps:
        found |= set(LISTED.findall(text))
    return found


def report(name: str, documented_here: set[str], accepted_here: set[str]) -> int:
    worst = 0
    # a difference we mean to have may be documented as ABSENT, which is not a lie
    lying = sorted(documented_here - accepted_here - FOREIGN - EXPECTED)
    if lying:
        worst = 1
        print(f"XX {name}: documented but the tool does not accept it: {', '.join(lying)}")
    quiet = sorted(accepted_here - documented_here)
    if quiet:
        worst = 1
        print(f"XX {name}: accepted but no manual mentions it: {', '.join(quiet)}")
    if not worst:
        print(f"ok {name}: {len(accepted_here)} option(s), every one documented and nothing "
              f"documented that is not there")
    return worst


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("only", nargs="*", choices=["java", "python", []],
                        help="which implementation; default: both, and they are compared")
    args = parser.parse_args()
    which = args.only or ["java", "python"]

    if not (PYTHON_TOOL / "run.py").is_file():
        sys.exit(f"the Python tool is not at {PYTHON_TOOL} (set SOCKSEEK_PY)")
    if "java" in which and not JAR.is_file():
        sys.exit(f"{JAR} is missing - run: gradlew fatJar")

    worst = 0
    taken: dict[str, set[str]] = {}
    if "java" in which:
        taken["java"] = accepted([
            ask([java(), "-jar", str(JAR), "--help"], REPO),
            ask([java(), "-cp", str(JAR), "dev.litetook.sockseek.cli.Cli", "--help"], REPO)])
        worst |= report("java", documented([REPO / "README.md", REPO / "docs" / "README.md"]),
                        taken["java"])
    sandbox = None
    if "python" in which:
        sandbox = Path(tempfile.mkdtemp(prefix="sockseek-docs-"))
        copy = e2e.sandbox(sandbox / "tool", index=False, files=False)
        taken["python"] = accepted([
            ask([sys.executable, "-X", "utf8", "run.py", "--help"], copy),
            ask([sys.executable, "-X", "utf8", "-m", "soulseek", "--help"], copy)])
        worst |= report("python",
                        documented([PYTHON_TOOL / "README.md",
                                    PYTHON_TOOL / "docs" / "README.md"]), taken["python"])
    if len(taken) == 2:
        only_python = sorted(taken["python"] - taken["java"] - EXPECTED)
        only_java = sorted(taken["java"] - taken["python"] - EXPECTED)
        if only_python or only_java:
            worst = 1
            print("XX the two tools do not accept the same options")
            if only_python:
                print(f"   only python: {', '.join(only_python)}")
            if only_java:
                print(f"   only java: {', '.join(only_java)}")
        else:
            print(f"ok both: the same options, apart from the {len(EXPECTED)} we mean to differ "
                  f"({', '.join(sorted(EXPECTED))})")
    if sandbox is not None:
        shutil.rmtree(sandbox, ignore_errors=True)
    return worst


if __name__ == "__main__":
    sys.exit(main())
