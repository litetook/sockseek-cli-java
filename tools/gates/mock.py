"""The real sockseek, driven by the real jar, with no network and no account.

    py tools/gates/mock.py

sockseek has a mock mode: point it at a folder of files with `--mock-files-dir` and it behaves
as though those files were shared on Soulseek. That is the only way to exercise the parts of
this tool that only exist while a download is happening - the live count, the record of what
happened, the skip that follows from it - without touching the owner's account or the network.

Unit tests cover each of those on fakes. This covers them on the real binary, which is the
thing that can surprise you: the `--on-complete` line, the word in `{failure-reason}`, the
order events arrive in, the exit code. Every one of those was measured here first, on
2026-10-17, and three of them were not what the help text promised.

What it does NOT cover, and nothing offline can: whether any of it behaves the same against
real uploaders.
"""
from __future__ import annotations

import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

import sandbox

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
JAR = REPO / "build" / "libs" / "sockseek-cli.jar"
MOCK_ARGS = "--no-config --mock-files-dir mock --mock-files-no-read-tags"

HERE_TRACKS = ["Daft Punk - Around the World", "Fatboy Slim - Praise You"]
NOWHERE = "Nobody Has This - Ghost Track"


def java() -> str:
    home = os.environ.get("JAVA_HOME")
    if home:
        exe = Path(home) / "bin" / ("java.exe" if os.name == "nt" else "java")
        if exe.is_file():
            return str(exe)
    return "java"


def build(into: Path) -> Path:
    (into / "Playlists").mkdir(parents=True)
    (into / "mock").mkdir()
    for name in HERE_TRACKS:
        (into / "mock" / f"{name}.mp3").write_bytes(b"\0" * 2_000_000)
    (into / "Playlists" / "mix.txt").write_text(
        "\n".join([f"{name} | 3:00" for name in HERE_TRACKS] + [f"{NOWHERE} | 3:00"]) + "\n",
        encoding="utf-8")
    (into / "sockseek.local.conf").write_text(sandbox.LOCAL_CONF, encoding="utf-8")
    shutil.copy2(sandbox.TOOL / "sockseek.exe", into / "sockseek.exe")
    shutil.copy2(sandbox.FIXTURE / "dependencies.yaml", into / "dependencies.yaml")
    return into


def run(folder: Path, *extra: str, fail: int = 0) -> str:
    """One run. `fail` makes sockseek drop that many mock downloads, to play a dead uploader.

    The sockseek arguments are built HERE rather than passed in: --sockseek-args is a single
    option, so a second one silently replaces the first - which is how the first version of
    this gate quietly tested nothing.
    """
    sandbox.reset(folder)
    args = MOCK_ARGS + (f" --mock-files-fail-downloads {fail}" if fail else "")
    done = subprocess.run(
        [java(), "-Dstdout.encoding=UTF-8", "-jar", str(JAR), "--only", "mix", "--yes",
         "--length-tol", "-1", *extra, f"--sockseek-args={args}"],
        cwd=folder, stdin=subprocess.DEVNULL, capture_output=True, text=True, encoding="utf-8",
        errors="replace", timeout=600)
    return (done.stdout or "") + (done.stderr or "")


def check(name: str, condition: bool, detail: str = "") -> int:
    print(("ok " if condition else "XX ") + name + ("" if condition else "  " + detail))
    return 0 if condition else 1


def main() -> int:
    if not JAR.is_file():
        sys.exit(f"{JAR} is missing - run: gradlew fatJar")
    if not (sandbox.TOOL / "sockseek.exe").is_file():
        sys.exit(f"no sockseek.exe at {sandbox.TOOL} (set SOCKSEEK_HOME)")
    where = Path(tempfile.mkdtemp(prefix="sockseek-mock-"))
    worst = 0
    try:
        folder = build(where / "tool")
        outcomes = folder / "outcomes.tsv"

        said = run(folder)
        worst |= check("the live count appears while the run is going",
                       ">> 1 of 3 done" in said and ">> 3 of 3 done" in said, said[-400:])
        worst |= check("the two shared tracks arrive",
                       all(f"OK      {name}" in said for name in HERE_TRACKS), said[-400:])
        written = outcomes.read_text(encoding="utf-8") if outcomes.is_file() else ""
        worst |= check("the one nobody has is written down as NoSearchResults",
                       "NoSearchResults" in written and NOWHERE in written, written)
        worst |= check("and nothing that arrived is",
                       not any(name in written for name in HERE_TRACKS), written)

        said = run(folder)
        worst |= check("the next run skips it instead of searching again",
                       "skipping 1 track(s)" in said, said[-400:])
        worst |= check("and says so rather than going quiet",
                       "skip_not_found_hours: 0 to search them anyway" in said, said[-400:])

        said = run(folder, "--skip-not-found-hours", "0")
        worst |= check("zero hours searches it again",
                       "skipping" not in said and ">> 1 of 1 done" in said, said[-400:])

        # a found track whose upload dies is a different class, and is never skipped
        shutil.rmtree(folder / "output", ignore_errors=True)
        run(folder, "--skip-not-found-hours", "0", fail=9)
        written = outcomes.read_text(encoding="utf-8")
        worst |= check("a dead upload is recorded as AllDownloadsFailed",
                       "AllDownloadsFailed" in written, written)

        # the default two hours of memory are in force here: the ghost track is held back, and the
        # two whose upload died are searched again. That split is the whole point of the file
        said = run(folder, fail=9)
        worst |= check("and is searched again next run, never skipped",
                       ">> 2 of 2 done" in said and "skipping 1 track(s)" in said,
                       said[-400:])
        written = outcomes.read_text(encoding="utf-8")
        worst |= check("failing the same way twice counts up",
                       any(parts[1] == "AllDownloadsFailed" and parts[2] == "2"
                           for parts in (line.split("\t") for line in written.splitlines())
                           if len(parts) > 2), written)
        return worst
    finally:
        shutil.rmtree(where, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
