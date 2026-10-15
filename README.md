# sockseek-cli — Java port

A port in progress of [sockseek-cli-playlist-downloader](https://github.com/litetook/sockseek-cli-playlist-downloader),
a batch downloader that drives [sockseek](https://github.com/fiso64/sockseek) to fetch a
playlist from Soulseek.

**The working tool is the Python one.** This repository is the port to Java 21, built phase by
phase, and the Python version stays the reference until this one matches it exactly. Phases 0-4
are done, so it runs a whole playlist from the menu to the report; what nobody has yet done is
a real download from Soulseek driven by this version - sockseek has only been started from it
in its mock mode.

Written with AI, which is worth knowing before running it.

## Where it stands

| Phase | What | State |
|---|---|---|
| 0 | JDK 21, Gradle wrapper | done |
| 1 | build, launchers, the opening menu | done |
| 2 | the pure logic: playlist, index, settings, importing, links | done |
| 3 | the process layer: running sockseek, the pinned tools, failure explanations | done |
| 4 | the run, the flags, the front end, exit codes | **done** — 212 tests |
| 5 | parity against the Python tool on real playlists | ten pairs identical; a real end-to-end run is what is left |
| 6 | the README, the launchers, what becomes of the Python version | |

The gate that decides the port is done is phase 5, and it is not a test suite: both tools are
run over the owner's own playlists and every line they print is diffed.

```bash
py tools/parity/run.py --offline     # eight pairs, no network
py tools/parity/run.py               # all ten, Spotify and live Soulseek included
```

## Build and run

Needs a **JDK 21** and nothing else — Gradle comes from the wrapper, which verifies the
distribution's sha256 before using it.

```bash
./gradlew build          # compiles, tests, writes build/libs/sockseek-cli.jar
./run.sh                 # macOS, Linux
run.cmd                  # Windows, also by double-click
```

The launchers find Java the way the Python tool finds Python, build the jar if it is missing,
and hand over. A double-click with no arguments keeps the window open so a message can be read.

## How it is built

- **Java 21**: records instead of the Python dataclasses, sealed types and pattern matching for
  the states, `ProcessHandle.descendants()` instead of a Windows-only `taskkill` branch.
- **Four libraries, each for one job it would be silly to hand-write**: picocli for the flags,
  Jackson for the one JSON document Spotify serves, commons-csv because sockseek's index quotes
  every field with a comma in it, commons-compress because the JDK unpacks zip and gzip but not
  tar. The YAML config keeps its own small parser: its warnings are pinned by tests, and a real
  YAML library would change them for no gain.
- **A hand-written fat jar task** rather than a shading plugin: ten lines, and one less thing to
  keep in step with Gradle.
- **Tests on behaviour, not on implementation**: questions and printed lines are injected, so a
  test reads like what a person would see.

## License

MIT — see [LICENSE](LICENSE). Not affiliated with Soulseek or sockseek.
