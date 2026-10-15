"""The front end of the Python tool, printed for the parity diff.

Nothing is downloaded and the engine is never started: it is replaced by a recorder, so what
this prints is the menu, the missing lists and the exact arguments the engine would have been
handed. Answers are printed by their index, never as text, so the two sides never differ over
how a language spells a repr.
"""
import os
import sys

APP = os.environ.get("SOCKSEEK_PY", r"C:\claude_tasks\Soulseek-Batch")
sys.path.insert(0, APP)

from pathlib import Path   # noqa: E402

import run                 # noqa: E402

PLAYLISTS = Path(APP) / "Playlists"
OUTPUT = Path(APP) / "output"

ANSWERS = ("", "1", "1,3", "3 1", "all", "*", "0", "99", "x", "\u00b2", "1,1", " 2 ",
           "\u00a01 ", "9999999999")

# flags -> the answers a person types; an empty tuple means nothing is typed at all
RUNS = (("--only soulseek-wanted --yes --dry-run", ()),
        ("--only cry-for-you-linds-edit-top30 --yes --length-tol 60 --verbose", ()),
        ("--only soulseek-wanted", ("y",)),
        ("--only soulseek-wanted", ("n",)),
        ("", ("1", "1,3", "n", "n")),
        ("", ("",)),
        ("--only nope", ()),
        ("--version", ()),
        ("--import nothing-here.m3u", ()))


def main() -> int:
    infos = run.scan(PLAYLISTS, OUTPUT)
    print("== table")
    for line in run.format_table(infos):
        print(line)
    for info in infos:
        print(f"== missing {info.name}")
        for line in run.format_missing(info):
            print(line)
        for warning in info.warnings:
            print("WARN", warning)
    print("== choice")
    for n, answer in enumerate(ANSWERS):
        picked = run.parse_choice(answer, len(infos))
        print(f"{n} -> {'?' if picked is None else picked}")
    for flags, said in RUNS:
        print(f"== run [{flags}] answers {len(said)}")
        answers = list(said)
        calls: list[list[str]] = []
        lines: list[str] = []

        def ask(prompt: str, answers=answers, lines=lines) -> str:
            lines.append(prompt)
            if not answers:
                raise EOFError
            return answers.pop(0)

        def engine(argv: list[str], calls=calls) -> int:
            calls.append(list(argv))
            return 0

        code = run.main(flags.split(), ask=ask, out=lines.append, engine=engine)
        for line in lines:
            print(line)
        print(f"-> code {code}")
        for argv in calls:
            print("-> engine " + " ".join(argv))
    return 0


if __name__ == "__main__":
    sys.exit(main())
