"""The manuals and the tool say the same thing.

    py tools/gates/docs.py

A manual that documents an option the tool does not accept, or a tool that accepts an option
no manual mentions, is a bug of the same kind as a wrong line of output: this text IS the
interface. It was written after the manual was found to still describe a `FAILED n/m` summary
line that the tool never printed (2026-10-15).

Options belonging to sockseek, yt-dlp or Gradle are named in the manuals on purpose and are
listed in FOREIGN below.

Until 2026-10-17 this also compared the two manuals of the Java tool and the Python tool it
was ported from. The Python tool is legacy now, by the owner's call, so only one side is left.
"""
from __future__ import annotations

import os
import re
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
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
    # the build, the test runner and the gates' own flags
    "--offline", "--tests", "--record",
    # sockseek again: the tool adds this one itself when --verbose is given
    "--log-file",
    # sockseek's machine-facing surface, named in the manual where the live count is explained
    "--on-complete", "--progress-json", "--skip-not-found",
    # setup.py's own flags, and setup.py is a program of the Python tool, not of its engine
    "--check", "--upgrade",
}
# accepted but deliberately absent from --help, so the manual may name what the tool will not
# list: --no-explain is a no-op kept because it is in the owner's fingers
HIDDEN = {"--no-explain"}


def java() -> str:
    home = os.environ.get("JAVA_HOME")
    if home:
        exe = Path(home) / "bin" / ("java.exe" if os.name == "nt" else "java")
        if exe.is_file():
            return str(exe)
    return "java"


def ask(command: list[str]) -> str:
    done = subprocess.run(command, cwd=REPO, capture_output=True, text=True, encoding="utf-8",
                          errors="replace", env={**os.environ, "PYTHONUTF8": "1"}, timeout=300)
    return ANSI.sub("", (done.stdout or "") + (done.stderr or ""))


def documented(files: list[Path]) -> set[str]:
    found: set[str] = set()
    for file in files:
        if not file.is_file():
            sys.exit(f"no such file: {file}")
        found |= set(OPTION.findall(file.read_text(encoding="utf-8")))
    return found


def main() -> int:
    if not JAR.is_file():
        sys.exit(f"{JAR} is missing - run: gradlew fatJar")
    accepted: set[str] = set()
    for text in (ask([java(), "-jar", str(JAR), "--help"]),
                 ask([java(), "-cp", str(JAR), "dev.litetook.sockseek.cli.Cli", "--help"])):
        accepted |= set(LISTED.findall(text))
    written = documented([REPO / "README.md", REPO / "docs" / "README.md"])
    worst = 0
    lying = sorted(written - accepted - FOREIGN - HIDDEN)
    if lying:
        worst = 1
        print(f"XX documented but the tool does not accept it: {', '.join(lying)}")
    quiet = sorted(accepted - written)
    if quiet:
        worst = 1
        print(f"XX accepted but no manual mentions it: {', '.join(quiet)}")
    if not worst:
        print(f"ok {len(accepted)} option(s), every one documented and nothing documented "
              f"that is not there")
    return worst


if __name__ == "__main__":
    sys.exit(main())
