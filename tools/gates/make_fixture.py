"""Build the test fixture: invented playlists that behave like real ones.

    py tools/gates/make_fixture.py

The snapshot gate needs a folder that does not move - 24 playlists, a thousand-odd lines, and
index files describing what is supposedly already downloaded. For half a day it was a copy of
the repository owner's own folder, and that was a mistake twice over: it went red every time
he downloaded a track, and it published his music taste to a public repository. Neither is
what a fixture is for.

So the data is generated here, deterministically, from a fixed seed. What matters for the
gate is not which songs they are but the SHAPE of them, and the shape is kept deliberately
nasty, because every one of these has broken something at least once:

  - non-ASCII titles, including Cyrillic and accented Latin
  - commas, ampersands, slashes, square and round brackets, apostrophes
  - lengths in m:ss, in h:mm:ss, in plain seconds, and lines with no length at all
  - duplicate lines, blank lines and comments
  - titles far longer than any console is wide
  - index rows for files that are not there, and files with no index row

Re-running this rewrites the fixture; the snapshots then have to be re-recorded, which is
deliberate - the fixture is data, and data that changes silently is how a gate starts lying.
"""
from __future__ import annotations

import csv
import random
import shutil
from pathlib import Path

HERE = Path(__file__).resolve().parent
FIXTURE = HERE / "fixture"
SEED = 20261017

ARTISTS = [
    "Aurora Beltrán", "Blue Kettle", "Cosmic Drift", "Döner Kebab Sound System",
    "Echo & The Mechanics", "Fjord", "Gábor Szabó Tribute", "Hollow Coast", "Iris Morningstar",
    "Járn", "Kettle Drum Jones", "Låpsley Covers", "Midnight Sorter", "Núria Vega",
    "Oblique Strategy", "Pavel Zaytsev", "Quiet Harbour", "Rafael's Theory", "Sunken Chapel",
    "Tölva", "Умовний Рефлекс", "Vølur", "Wandering Spoke", "Ясне Поле",
]
WORDS = [
    "Acid", "Amber", "Backroom", "Cathedral", "Daylight", "Drift", "Ember", "Fracture",
    "Glasshouse", "Harbour", "Iron", "Jetty", "Kelvin", "Lantern", "Mirage", "Northline",
    "Orbit", "Pressure", "Quarry", "Rivet", "Slipstream", "Tideline", "Undertow", "Vertigo",
    "Wharf", "Xenon", "Yellowline", "Zephyr",
]
TAILS = [
    "", "", "", " (Extended Mix)", " (Radio Edit)", " [FREE DOWNLOAD]", " (feat. Núria Vega)",
    " - Original Mix", " (Pavel Zaytsev Remix)", " / B-side", " (Live at the Quarry, 1998)",
]
# name -> how many tracks; the long tail of small playlists is what the real folder looks like
PLAYLISTS = {
    "ambient-night": 25, "breaks": 29, "chill": 69, "classics": 10, "dnb": 38,
    "dubstep-bass": 45, "example": 3, "hip-hop": 54, "house": 84, "one-track": 1,
    "other": 36, "phonk-dark": 31, "pop": 90, "rock-metal": 43, "techno-acid": 27,
    "techno-hard": 107, "techno-melodic": 61, "techno": 40, "trance": 33, "ukrainian": 146,
    "wanted": 4, "weird-edges": 12, "with-links": 8, "nothing-here": 17,
}


def title(rng: random.Random) -> str:
    name = " ".join(rng.sample(WORDS, rng.choice([1, 1, 2, 2, 3])))
    return name + rng.choice(TAILS)


def length(rng: random.Random) -> str:
    """m:ss mostly, sometimes h:mm:ss or plain seconds, sometimes nothing at all."""
    seconds = rng.randint(95, 9000)
    shape = rng.random()
    if shape < 0.08:
        return ""
    if shape < 0.14:
        return str(seconds)
    if seconds >= 3600:
        return f"{seconds // 3600}:{seconds % 3600 // 60:02d}:{seconds % 60:02d}"
    return f"{seconds // 60}:{seconds % 60:02d}"


def line(rng: random.Random) -> str:
    text = f"{rng.choice(ARTISTS)} - {title(rng)}"
    if rng.random() < 0.04:
        text += " " + "very " * 20 + "long"   # wider than any console
    tail = length(rng)
    return f"{text} | {tail}" if tail else text


def playlist(rng: random.Random, count: int) -> list[str]:
    lines = [line(rng) for _ in range(count)]
    if count > 6:
        lines.insert(rng.randrange(count), lines[0])        # a duplicate, which must collapse
        lines.insert(rng.randrange(count), "")              # a blank line
        lines.insert(rng.randrange(count), "# a comment")   # and a comment
    return lines


def index(rng: random.Random, lines: list[str], folder: Path) -> None:
    """An index for some of them: a few downloaded, a few failed, a few never searched."""
    real = [one for one in lines if one.strip() and not one.startswith("#")]
    rows = []
    for one in real:
        if rng.random() < 0.45:
            continue   # never seen by sockseek at all
        name = one.split(" | ")[0]
        state = rng.choices(["1", "2", "3", "0"], weights=[45, 35, 15, 5])[0]
        rows.append({
            "filepath": f"{name}.mp3".replace("/", "_") if state in ("1", "3") else "",
            "artist": "", "album": "", "title": name,
            "length": "-1", "tracktype": "0", "state": state,
            "failurereason": "9" if state == "2" else "0",
        })
    if not rows:
        return
    folder.mkdir(parents=True, exist_ok=True)
    with (folder / "_index.csv").open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)


def main() -> None:
    rng = random.Random(SEED)
    shutil.rmtree(FIXTURE / "Playlists", ignore_errors=True)
    shutil.rmtree(FIXTURE / "output", ignore_errors=True)
    (FIXTURE / "Playlists").mkdir(parents=True, exist_ok=True)
    for name in sorted(PLAYLISTS):
        lines = playlist(rng, PLAYLISTS[name])
        (FIXTURE / "Playlists" / f"{name}.txt").write_text(
            "\n".join(lines) + "\n", encoding="utf-8", newline="")
        if name != "nothing-here":   # one playlist sockseek has never been asked about
            index(rng, lines, FIXTURE / "output" / name)
    total = sum(PLAYLISTS.values())
    print(f"wrote {len(PLAYLISTS)} playlists, {total} lines, seed {SEED}")


if __name__ == "__main__":
    main()
