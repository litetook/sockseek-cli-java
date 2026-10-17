# sockseek-cli

Batch-download playlists from Soulseek. You keep plain text files like
`Artist - Title | 4:09`; this shows how much of each playlist is already on disk, searches
**only what is missing** after you say yes, and tells you what did not arrive and why.

It drives [sockseek](https://github.com/fiso64/sockseek) — one process per playlist — and adds
the things a batch run needs around it: a single confirmation for the whole set, a live count,
a watchdog for a run that has gone quiet, and a memory of what nobody was sharing yesterday.

Written with AI, which is worth knowing before running it.

```bash
run.cmd              # Windows: pick a playlist, see what is missing, confirm
./run.sh             # macOS, Linux
run.cmd --all --yes  # every playlist, no questions at all - the scheduled run
```

Needs a **JDK 21** and nothing else. The launcher builds the jar if it is missing; sockseek and
yt-dlp arrive on demand at pinned versions, each checked against its sha256 first.

**[docs/README.md](docs/README.md) is the manual** — the playlist format, the imports, every
setting explained twice over (what it is for, and what happens behind it), what to do about a
track that will not come down, and an honest list of what this cannot do.

---

## What it adds to sockseek

| | |
|---|---|
| **One question** | every missing list first, then the price of the lot — playlists, tracks, and the minutes the 34-per-220-s search limiter alone will cost — then one `y` |
| **A live count** | `>> 12 of 35 done, 9 downloaded`, while the run is going, across the whole run rather than one playlist |
| **A silence watchdog** | a playlist that has finished nothing for `stall_timeout_s` is stopped and said to have gone quiet, not left to burn its wall clock |
| **A memory of last time** | `outcomes.tsv`: how each track ended, with a run counter. A track nobody was sharing is not searched again for `skip_not_found_hours`; every other failure is counted and never skipped, so "this one has failed four runs running" is something you can see |
| **Only what is missing** | a track already on disk is never sent to sockseek, so re-running the same playlist tomorrow is the normal way to pick up the rest |
| **Reasons in words** | `reason=9` in sockseek's index means both "nobody had it" and "found and rejected"; `--explain` searches again with every condition off and says which |
| **The screen, kept** | `logs/session_<date>.log` holds everything you saw, including the questions you were asked |

## How it works

The live count, the watchdog and the cache all rest on one thing: sockseek's `--on-complete`
runs a command as each track ends, and that command is an ordinary child process — so the
console stays sockseek's, with its own display and its own keys, while this tool reads a line
per finished track from a file. The alternative, reading `--progress-json` and typing into
sockseek's stdin, would have taken the terminal away and does not work anyway; the manual says
why in detail.

## Build

```bash
./gradlew build          # compiles, tests, writes build/libs/sockseek-cli.jar
./gradlew test           # 243 tests
```

- **Java 21.** Records instead of dataclasses, `ProcessHandle.descendants()` instead of a
  Windows-only `taskkill` branch.
- **Four libraries, each for one job it would be silly to hand-write**: picocli for the flags,
  Jackson for the one JSON document Spotify serves, commons-csv because sockseek's index
  quotes every field with a comma in it, commons-compress because the JDK unpacks zip and gzip
  but not tar. The YAML config keeps its own small parser: its warnings are pinned by tests.
- **A hand-written fat jar task** rather than a shading plugin: ten lines, one less thing to
  keep in step with Gradle.
- **Tests on behaviour**: questions and printed lines are injected, so a test reads like what
  a person would see.

## How it is checked

Three gates beyond the unit tests, all offline — no network, no Soulseek account, and nothing
ever run inside the owner's own folder:

```bash
py tools/gates/snapshot.py     # the whole tool over 24 real playlists, against a recording
py tools/gates/mock.py         # the real sockseek binary in mock mode, end to end
py tools/gates/docs.py         # the manuals against the options the tool really accepts
```

**snapshot** answers "did anything change that I did not mean to change?". It runs the whole
command over the owner's real playlists in three states of the output folder and diffs every
line against a recorded answer. A deliberate change is re-recorded with `--record`, and that
diff goes in the commit — it says in plain text what every playlist will now see.

**mock** is the one that can surprise you. sockseek has a mock mode, so the live count, the
record of what happened and the skip that follows from it are exercised against the real
binary. Three of today's measurements came out of it, and none of them matched the help text.

This was a port of
[sockseek-cli-playlist-downloader](https://github.com/litetook/sockseek-cli-playlist-downloader),
and until 2026-10-17 the gate was that Python tool run side by side with this one, every line
diffed. It answered "is the port faithful?", and the answer was yes. Once the two deliberately
diverged, each new feature cost an entry in an ignore list and the gate was measuring the
ignore list. The Python tool is legacy now; the recording took its place.

## License

MIT — see [LICENSE](LICENSE). Not affiliated with Soulseek or sockseek.
