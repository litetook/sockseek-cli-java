# sockseek-cli

Batch-download playlists from Soulseek. You keep plain text files like
`Artist - Title | 4:09`; this shows how much of each playlist is already on disk, searches
**only what is missing** after you say yes, and tells you what did not arrive and why.

It drives [sockseek](https://github.com/fiso64/sockseek) — one process per playlist — and adds
the things a batch run needs around it: a single confirmation for the whole set, a live count,
a watchdog for a run that has gone quiet, and a memory of what nobody was sharing yesterday.

Written with AI, which is worth knowing before running it.

```bash
run.cmd          # Windows
./run.sh         # macOS, Linux
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
| **A failure cache** | a track nobody was sharing is not searched for again for `skip_not_found_days` — and only that one class of failure is ever remembered |
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

This is a port of [sockseek-cli-playlist-downloader](https://github.com/litetook/sockseek-cli-playlist-downloader),
and that Python tool is kept as the **oracle**. The gate is not a test suite: both tools are
run over the same real playlists and every line they print is diffed.

```bash
py tools/parity/run.py --offline     # eight module pairs, no network
py tools/parity/run.py               # all ten, Spotify and live Soulseek included
py tools/parity/e2e.py               # both whole tools, three states of the export folder
py tools/parity/docs.py              # the manuals against the options the tools really accept
```

Where the two deliberately differ — the single confirmation, the live lines, `--explain`
changing sides, the two settings the Python tool never had — the difference is named in the
gate rather than hidden. The gates work from a sandbox copy and never run anything inside the
owner's own folder.

## License

MIT — see [LICENSE](LICENSE). Not affiliated with Soulseek or sockseek.
