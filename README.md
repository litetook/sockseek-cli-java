# sockseek-cli

Batch-download playlists from Soulseek, unattended.

You keep plain text files like `Artist - Title | 4:09`. This shows how much of each playlist
is already on disk, searches **only what is missing**, downloads it, and tells you what did
not arrive and why. Run it again tomorrow and it picks up whatever came online since — which
is not a workaround, it is how Soulseek works.

It drives [sockseek](https://github.com/fiso64/sockseek) and adds what a batch run needs
around it: one confirmation for the whole night, a live count, a watchdog for a run that has
gone quiet, a memory of what nobody was sharing, and a brake that keeps the search rate under
the limit that gets accounts banned.

```
Playlists:

  #  playlist          tracks  here  missing
  -  ----------------  ------  ----  -------
  1  techno-hard          107    72       35
  2  house                 84    84        0

What to download? (1, or 1,2, or all; [b] back, Enter quits): 1

To download: 1 playlist(s), 35 missing track(s). 4 of them were searched for recently
and nobody had them, so 31 will be searched.
That is about 31 search(es); sockseek is allowed 34 per 220 s and each playlist gets its
own budget, so waiting for the limiter adds about 0 min in all.

Download these 31? [y] yes  [n] no: y

[1/1] techno-hard: searching 31 missing track(s) (4 held back)
>> 12 of 31 done, 9 downloaded
>> 13 of 31 done, 9 downloaded  (NoSearchResults: Quiet Harbour - Amber Jetty | 3:12)
...
[1/1] techno-hard: 24 of 31 arrived - 24 of 31 in all
```

## Get it

Download `sockseek-cli.jar` from
[Releases](https://github.com/litetook/sockseek-cli-java/releases), put it in a folder of its
own, and run it. You need **a JDK 21** and nothing else — sockseek itself and yt-dlp are
fetched on demand at pinned versions, each checked against its sha256 before it is put in
place. Nothing is installed system-wide and nothing needs admin rights.

```bash
java -jar sockseek-cli.jar              # pick a playlist, see what is missing, confirm
java -jar sockseek-cli.jar --all --yes  # every playlist, no questions - the scheduled run
```

The first run writes three config files and stops, because it cannot download anything until
it knows who you are: put your Soulseek login in `sockseek.local.conf`, drop `.txt` playlists
into `Playlists/`, and run it again. Every setting is in those files with a line saying what
it does.

Building from source instead: `./gradlew build`, and `run.cmd` / `./run.sh` do it for you.

## What it adds to sockseek

| | |
|---|---|
| **One question** | every missing list first, then the price of the lot — playlists, tracks, and the minutes the search limiter alone will cost — then one `y` |
| **A live count** | `>> 12 of 31 done, 9 downloaded`, while it is running, across the whole run rather than one playlist |
| **A silence watchdog** | a playlist that has finished nothing *and* received no bytes for `stall_timeout_s` is stopped and said to have gone quiet, not left to burn its wall clock |
| **A memory of last time** | `outcomes.tsv`: how each track ended, with a run counter. A track nobody was sharing is not searched again for `skip_not_found_hours`; every other failure is counted and never skipped, so "this one has failed four runs running" is something you can see |
| **A brake on searching** | the Soulseek server bans an account that searches too fast, and sockseek's own budget only covers one process. This keeps every playlist together under the limit |
| **Only what is missing** | a track already on disk is never sent to sockseek |
| **Reasons in words** | `reason=9` in sockseek's index means both "nobody had it" and "found and rejected"; `--explain` searches again with every condition off and says which |
| **The screen, kept** | `logs/session_<date>.log` holds everything you saw, including the questions you were asked |

**[docs/README.md](docs/README.md) is the manual** — the playlist format, importing from
Spotify and SoundCloud, every setting explained twice over (what it is for, and what happens
behind it), what to do about a track that will not come down, and an honest list of what this
cannot do.

## How it works

The live count, the watchdog and the memory all rest on one thing: sockseek's `--on-complete`
runs a command as each track ends, and that command is an ordinary child process — so the
console stays sockseek's, with its own display and its own keys, while this tool reads a line
per finished track from a file. The alternative, reading `--progress-json` and typing into
sockseek's stdin, would have taken the terminal away and does not work anyway; the manual says
why in detail.

## How it is checked

Three gates beyond the unit tests, all offline — no network, no Soulseek account:

```bash
./gradlew test                 # 258 tests
py tools/gates/snapshot.py     # the whole tool over 24 playlists, against a recording
py tools/gates/mock.py         # the real sockseek binary in mock mode, end to end
py tools/gates/docs.py         # the manuals against the options the tool really accepts
```

**snapshot** answers "did anything change that I did not mean to change?". It runs the whole
command over a generated fixture in three states of the output folder and diffs every line
against a recorded answer. A deliberate change is re-recorded with `--record`, and that diff
goes in the commit — it says in plain text what every playlist will now see.

**mock** is the one that can surprise you. sockseek has a mock mode, so the live count, the
outcome record and the skip are exercised against the real binary.

This was a port of
[sockseek-cli-playlist-downloader](https://github.com/litetook/sockseek-cli-playlist-downloader),
and until 2026-10-17 the gate was that Python tool run side by side with this one, every line
diffed. It answered "is the port faithful?", and the answer was yes. The recording took its
place once the two deliberately diverged.

## Built with

**Java 21.** Records instead of dataclasses, `ProcessHandle.descendants()` instead of a
Windows-only `taskkill`. Four libraries, each for one job it would be silly to hand-write:
picocli for the flags, Jackson for the one JSON document Spotify serves, commons-csv because
sockseek's index quotes every field with a comma in it, commons-compress because the JDK
unpacks zip and gzip but not tar. The YAML config keeps its own small parser, its warnings
pinned by tests. A hand-written fat jar task rather than a shading plugin.

Developed and measured on Windows; the pins cover macOS and Linux, but neither has been tried.

Written with AI, directed by the repository owner, with no second human reviewer. What has
been measured is in the manual, dated; what has not is marked ⚠.

## License

MIT — see [LICENSE](LICENSE). Not affiliated with Soulseek or sockseek.
