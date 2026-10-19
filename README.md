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

> **Written entirely by an AI.** Code, tests, checks and documentation were produced and
> tested by **Claude Opus 5** at high reasoning effort, directed by the repository owner.
> **No human has reviewed the code.** It handles your Soulseek password and writes to your
> disk — worth knowing before you run it.

```
Playlists:

  #  playlist  tracks  here  missing
  -  --------  ------  ----  -------
  1  starter        5     0        5

To download: 1 playlist(s), 5 missing track(s).
That is about 5 search(es); sockseek is allowed 34 per 220 s and each playlist gets its
own budget, so waiting for the limiter adds about 0 min in all.

[1/1] starter: searching 5 missing track(s)
>> 1 of 5 done, 1 downloaded
>> 2 of 5 done, 2 downloaded
>> 3 of 5 done, 2 downloaded  (NoSearchResults: Moby - Porcelain | 4:01)
>> 4 of 5 done, 3 downloaded
>> 5 of 5 done, 4 downloaded
[time] OK      Daft Punk - Around the World | 7:09
[time] FAILED  state=2 reason=9 (no candidate passed the name/length/quality conditions)  Moby - Porcelain | 4:01
[time] run #0001: 4 arrived, 1 still missing, 4 of 5 in the folder
[1/1] starter: 4 of 5 arrived - 4 of 5 in all
```

Five minutes from nothing to that, below.

## Before you start

Two things, and only one of them is on you:

| What | Why |
|---|---|
| **A Soulseek account** | the network only answers to a logged-in user. Make one in the [Soulseek client](https://www.slsknet.org/) if you have none |
| **About 300 MB free, and outgoing internet** | 70 MB of it is Java, sockseek and the jar; the rest is music |

**Java you do not install. sockseek you do not install.** The launcher fetches whatever is
missing, each download pinned and checked against its SHA-256 before it is used, and says on
screen exactly what it is doing. Everything lands in the one folder: nothing goes into the
registry, nothing needs admin rights or `sudo`, and deleting the folder undoes all of it.

Windows is the only platform this has been run on. The jar is platform-independent and
`run.sh` covers macOS and Linux from the same pins, but neither has been tried.

## Quick start

**1. Make a folder and put the launcher in it** - `run.cmd` on Windows, `run.sh` on macOS or
Linux (`chmod +x run.sh`). It needs `dependencies.yaml` and the `tools/` folder beside it,
so the simplest honest way is to download this repository's zip and keep those names.

**2. Run it.** One command, and it pulls the rest itself:

```bash
run.cmd
```

```
Java 21 was not found, so this fetches a private copy into ...\jdk.
About 47 MB, pinned and sha256-checked, nothing installed system-wide, no admin
rights needed. Deleting that folder undoes it. The log is ...\logs\install.log.
downloading Java 21.0.12.1_1 (about 47 MB)
  https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/...zip
sha256 ok (Java 21.0.12.1_1)
installed Java 21.0.12.1_1 -> ...\jdk
sockseek-cli.jar is not here yet; taking it from the latest release.
downloading v1.4.0 sockseek-cli.jar (5.4 MB)
installed sockseek-cli.jar -> ...\sockseek-cli.jar
WARN created config.yaml from the built-in template
```

The same lines go into `logs/install.log`, so a download that went wrong is readable
afterwards. Java already on the machine (21 or newer) is used as it is and nothing is fetched.

**3. Type your login, once.** The tool asks on the first run and writes it to
`config.local.yaml` itself. The password is not echoed while you type it.

```
Your Soulseek login, once: it is written to config.local.yaml and never asked again.
(the same account you use in the Soulseek client; git never sees that file)
Username: your-login
Password:
Saved to ...\config.local.yaml - edit that file if it ever changes.
```

That file stays yours to edit by hand - that is how a password this prompt would mangle gets
fixed, and how a second machine is set up by copying one file:

```yaml
sockseek:
  username: your-login
  password: your-password
```

**4. And it goes straight on** to the same menu every later run opens with - no "now start it
again". sockseek itself is fetched here, the first time a download needs it, pinned and
sha256-checked the same way.

```
Where from?

  1  download a playlist from a .txt file
  2  save a .txt from a Spotify link, then download it
  3  save a .txt from a SoundCloud link, then download it
  4  one track from a link, into unsorted
```


**5. Write a playlist.** One `.txt` file per playlist in `Playlists/`. The file name becomes
the name of the playlist and of its download folder; one track per line, the length after a
`|`. Save this as `Playlists/starter.txt` — five records a lot of people are sharing, so
something should come down within a minute or two:

```
Daft Punk - Around the World | 7:09
Fatboy Slim - Praise You | 5:23
The Chemical Brothers - Hey Boy Hey Girl | 4:46
Moby - Porcelain | 4:01
Massive Attack - Teardrop | 5:29
```

A length may be `m:ss`, `h:mm:ss` or plain seconds, and a line with no length at all is
searched without a length filter. Lines starting with `#` are ignored.

**6. Run it.**

```bash
run.cmd --only starter --yes
```

(`java -jar sockseek-cli.jar --only starter --yes` is the same thing, once Java is there.)

That is the run printed at the top of this page. The first time, this is also where
sockseek is fetched. Files land in `output/starter/`, and running the same command again will
tell you there is nothing left to do.

If the login is wrong, sockseek says so in the middle of the run and the tool stops with
exit 1:

```
[soulseek] Logging in as your-login..
[error] [cli] Soulseek login failed: The server rejected login attempt: INVALIDPASS
ERROR sockseek exited with an error and wrote no index: nothing was searched
```

The lengths above are for the usual album versions, and a remaster or a single edit can be a
minute out — which the length filter, the strict one, will then refuse. So if something
comes back as `NoSearchResults` and you are sure it exists, that is the first thing to
loosen:

```bash
java -jar sockseek-cli.jar --only starter --yes --length-tol 90   # or -1 for no filter
```

**7. One track, right now.** Run it with no arguments and the first question offers four
ways in; the fourth takes a single Spotify or SoundCloud link and downloads that one track:

```
Where from?

  1  download a playlist from a .txt file
  2  save a .txt from a Spotify link, then download it
  3  save a .txt from a SoundCloud link, then download it
  4  one track from a link, into unsorted

Pick one (Enter quits): 4

Paste the Spotify or SoundCloud link ([b] back, Enter cancels): https://open.spotify.com/track/...
Reading https://open.spotify.com/track/...
  + Daft Punk - Around the World | 7:10
1 track(s) added to unsorted.txt; they land in unsorted/
```

`unsorted` is a playlist like any other - `Playlists/unsorted.txt`, music in
`output/unsorted/`. It grows each time you do this, the same track is never added twice, and
everything else works on it as usual: the cache, the length filter, `--only unsorted`.

**8. Now your own.** Drop more `.txt` files into `Playlists/`, or import them rather than
typing them out:

```bash
java -jar sockseek-cli.jar --import "https://open.spotify.com/playlist/<id>"
java -jar sockseek-cli.jar --import "https://soundcloud.com/<artist>/sets/<set>" --as chill
java -jar sockseek-cli.jar --import exported.csv --as techno-2026
```

Then take the lot in one go. This asks nothing at all, which is what an overnight run wants:

```bash
java -jar sockseek-cli.jar --all --yes
```

sockseek itself is fetched the first time it is needed, at the version pinned in
`dependencies.yaml`, checked against its sha256 before it is put in place. yt-dlp arrives
the same way if you import from SoundCloud. Nothing is installed system-wide and nothing
needs admin rights.

**9. Run it again tomorrow.** What you can get depends on who is online, so a second run
over the same playlist is the normal way to pick up the rest — it searches only what is
still missing, and skips what nobody was sharing an hour ago.

Building from source instead of downloading: `./gradlew build`, and `run.cmd` / `./run.sh` do
it for you.

### Where things end up

| | |
|---|---|
| `Playlists/<name>.txt` | your lists - the input |
| `output/<name>/` | the audio, flat, named `Artist - Title.ext` |
| `output/<name>/_index.csv` | sockseek's index: the only link between a playlist line and a file |
| `outcomes.tsv` | how each track ended last time, with the time and a run counter |
| `logs/session_<date>.log` | everything you saw on screen, one file per start |
| `logs/run_<NNNN>_<date>.log` | one run of one playlist |
| `work/` | generated lists and the merged config - **holds your login in plain text**; safe to delete |
| `config.yaml` | every setting, in blocks; written from the template on the first run |
| `config.local.yaml` | your login, and anything you want to override on this machine - **git-ignored, you write it yourself** |
| `sockseek.exe` | fetched into the same folder on first use; `paths.sockseek_bin` moves it |

---

## Settings

Nothing is hard-coded: every folder, every file and every number below is a setting. Paths
are relative to the folder you run in — that folder is the installation, and `sockseek.exe`
lands in it — so an absolute path is only ever needed to send something elsewhere, like
`music: D:\music`.

One file, `config.yaml`, in blocks that say **what a value affects**: `paths`, `rate_limit`
(the whole run), `playlist`, `track`, `cache`, and `sockseek` - the last one being sockseek's
own options, its names, passed through untouched. The full name of a setting is `block.key`.
Every key carries a comment, so you can work in the file without coming back here. Your login
goes in `config.local.yaml`, which overrides any key and never reaches git. Every value can
also be given on the command line, and the order is
**command line > config.local.yaml > config.yaml > built-in default**. A value may carry its
unit (`300s`, `60min`, `2h`) and the wrong unit is a warning, not a silent number. The run log opens with
each value and where it came from, so "why is it this number?" is never a guess.

The key names changed on 2026-10-18 - `retries` counted goes rather than repeats, and
`skip_not_found_hours` named the one thing that word cannot tell apart. Old names and old
flags are still accepted, with a warning naming the new one.

### `config.yaml`

| Key | Default | What it does |
|---|---|---|
| `paths.sockseek_bin` | `sockseek.exe` | the sockseek binary; `.exe` is added or dropped to match what is really there |
| `paths.playlists` | `Playlists` | folder of playlist `.txt` files |
| `paths.music` | `output` | where the audio goes, one sub-folder per playlist |
| `paths.work` | `work` | generated list files and the merged sockseek config; rebuilt every run and safe to delete - but it holds the login in plain text while one is going |
| `paths.logs` | `logs` | run logs, session logs and the run counter |
| `track.max_length_diff_s` | `45` | **hard filter.** A shared file may differ from your length by this many seconds; `-1` turns it off. sockseek's own 3 rejects the very file you asked for, because playlist lengths run short of the copies people share |
| `playlist.attempts` | `1` | goes at one playlist; `1` is one go and no repeat. attempts per playlist. A second attempt does earn tracks, but it doubles the clock and starts a fresh search budget, which raises the ban risk |
| `playlist.attempt_timeout_min` | `60` | wall clock for **one attempt at one playlist**, in minutes; `0` = none |
| `playlist.stall_timeout_s` | `300` | stop a playlist when nothing has finished **and** nothing has arrived on disk for this long; `0` = never. Not a deadline per track - there is no way to stop one transfer from outside |
| `cache.skip_absent_h` | `2` | do not search again for a track nobody was sharing, if that was within this many hours; `0` = always search. Fractions work |
| `rate_limit.searches_per_window` | `34` | the brake: all playlists together stay under this many searches per `rate_limit.window_s`, by pausing between them; `0` = no pause. The Soulseek server bans an account that goes faster |
| `rate_limit.window_s` | `220` | the length of that window, in seconds |
| `track.replace_if_better` | `false` | **off by default, because on it touches your library.** On, every track is searched for again - the ones already on disk included - and a new copy is kept only when it is better **by the same thing the quality ladder sorts by, the bitrate**. The playlist says how long the track is and the file says how big it is, so bytes x 8 / seconds is its bitrate to within a few percent - no file is ever opened. A 128 kbps mp3 taken on a bad night is replaced by a 320; a 320 is never replaced by a 128. Lossless always beats lossy. A file that is not the length the playlist claims is not compared at all. What arrives lands in a folder of its own first, so nothing of yours is deleted until a winner is picked, and a track nobody is sharing leaves your copy exactly as it was. Off, a track on disk is not searched for at all |
| `track.max_sources` | `3` | how many different uploaders to try for one track before giving up on it this run. sockseek calls this `max-retries`, which sounds like trying the same one again; what it counts is different people |
| `track.format_order` | *(empty)* | formats you want, best first, comma separated — `mp3@256, flac, mp3@128`. Each is a separate pass over whatever is still missing with that format **required**, and `@192` is a floor for that pass only: without it the ladder runs downhill, because a 96 kbps mp3 taken first means the flac that was there is never asked for. The last pass has no format and no floor, so nothing is lost. The only way to order formats at all: sockseek's own `pref-format` is unordered by its documentation. It costs searches — every pass is a search per still-missing track, and the price before the one `y` says so |
| `cache.enabled` | `true` | `false` makes `outcomes.tsv` not exist for the run: nothing read, nothing written |

> **How a failure knows what it is.** sockseek reports "nobody on the network had it" and
> "several people had it and every copy was refused by your filters" with the **same** code —
> on a real run that one code covered 104 of 135 failures. The run tells them apart from what
> it was asking for, while it is going and without a single extra search: the last pass asks
> for any format, so if nothing could refuse a file either, "no results" can only mean nobody
> has it, and the row says `NotOnNetwork`. With a hard condition in force the row says
> `NoMatchUnderConditions` instead and names them, and that class is never skipped. Each row
> carries the conditions it was written under, so turning a filter off makes the tool ask
> again at once instead of waiting out `cache.skip_absent_h`. To ask the network about one
> track on purpose: `--why "Artist - Title"`.

### the `sockseek:` block

sockseek's own options without the leading `--`, inside `config.yaml`; `sockseek --help` is
the full list. They are grouped into `refuse`, `prefer`, `pick_source` and `drop_source` -
sub-blocks of ours, for reading only, flattened away before sockseek sees them. A key under
`refuse` can mean the track never arrives; a `pref-` key only changes the order.

| Key | Default here | What it does |
|---|---|---|
| `max-stale-time` | `12000` | milliseconds with no bytes arriving before this source is dropped |
| `fails-to-downrank` | `1` | an uploader that fails once drops down the ranking |
| `fails-to-ignore` | `2` | an uploader that fails twice is left out for the rest of the run |
| `min-bitrate` | *(off)* | **a hard floor:** a file below this is refused outright. Shipped commented out - at 256 it refused every mp3 at 192 or 224, which was 104 of 135 failures on a real run, and a refusal is indistinguishable from "nobody had it". A floor refuses the COMMON copies, which is why a global one costs too much; a floor on one pass of `track.format_order` is the cheap version |
| `max-samplerate`, `max-bitdepth` | `48000`, `16` | **the hard ceiling,** on since 2026-10-19. The opposite bargain to the floor: it refuses the RARE copies - 6 of 57 files were above CD quality and held 26% of all the bytes. 16 bit / 48 kHz is exactly CD material, so nothing a CD could hold is refused, and it caps what one stubborn track can cost (worst measured: 102 MB for 5:15). Safe because sockseek accepts a share that declares no properties unless `--strict-conditions` is set, which it is not (measured from its own `--help`) |
| `pref-min-bitrate` | `320` | everything `pref-` only **ranks** candidates; it never refuses one |
| `pref-max-bitrate` | `1411` | CD lossless. Anything above it ranks last and arrives only when there is nothing cheaper |
| `pref-format` | `flac,mp3` | both equally preferred; wav is left out, so it ranks below them |
| `fast-search` | `true` | take the first candidate that satisfies the preferred conditions instead of waiting for the whole search |
| `fast-search-delay` | `2000` | but collect candidates for this many milliseconds first |
| `fast-search-min-up-speed` | `200` | a pass mark for that shortcut only; a slower uploader stays in the normal set. The unit is undocumented and unproven |
| `pref-strict-title`, `pref-strict-artist` | `true` | prefer files whose path contains the title, the artist |

Your login lives in `config.local.yaml`, under the same `sockseek:` block, and that file
overrides any key above as well - so it is also the place for a machine-specific tweak.
`--sockseek-conf FILE` adds one more layer for a single run, in sockseek's own syntax.

### On the command line

Anything in the tables above, as a flag: `--replace-if-better`, `--max-length-diff 90`,
`--stall-timeout 0`,
`--skip-absent 0`, `--searches-per-window 20`, `--searches-window 300`,
`--max-sources 5`, `--format-order mp3,flac`,
`--no-cache`, and the folders as `--playlists`, `--output`, `--work`, `--logs`,
`--sockseek`. And these, which exist only there:

| Flag | What it does |
|---|---|
| `--all` | take every playlist without asking which |
| `--yes` | skip the confirmation; together with `--all`, no questions at all |
| `--only NAME` | one playlist, by file name without `.txt` |
| `--import WHAT` | turn a link or an exported file into a playlist, and stop |
| `--as NAME`, `--overwrite` | the name for that import, and permission to replace |
| `--cookies-from-browser B` | read a private SoundCloud set with your browser's own login |
| `--why TEXT` | download nothing: search once more with every condition off for each missing track whose line contains TEXT, and say what is really out there. An empty TEXT asks about all of them (`--explain` is the old name for that) |
| `--preview` | print what the search returns, download nothing |
| `--dry-run` | print the sockseek command, run nothing |
| `--verbose` | sockseek's own log, in `logs/sockseek_<run>_<name>.log` |
| `--sockseek-args "..."` | raw arguments passed straight through to sockseek |

Exit codes: `0` the run worked and tracks may still be missing, `1` the tool failed, `2` a
setup problem.

**[docs/README.md](docs/README.md) is the full manual** - the same settings explained twice
over, with what happens behind each and the measurement behind every default, what to do
about a track that will not come down, and an honest list of what this cannot do.

## What it adds to sockseek

| | |
|---|---|
| **One question** | every missing list first, then the price of the lot — playlists, tracks, and the minutes the search limiter alone will cost — then one `y` |
| **A live count** | `>> 12 of 31 done, 9 downloaded`, while it is running, across the whole run rather than one playlist |
| **A silence watchdog** | a playlist that has finished nothing *and* received no bytes for `stall_timeout_s` is stopped and said to have gone quiet, not left to burn its wall clock |
| **A memory of last time** | `outcomes.tsv`: how each track ended, with a run counter. A track nobody was sharing is not searched again for `cache.skip_absent_h`; every other failure is counted and never skipped, so "this one has failed four runs running" is something you can see |
| **A brake on searching** | the Soulseek server bans an account that searches too fast, and sockseek's own budget only covers one process. This keeps every playlist together under the limit |
| **Only what is missing** | a track already on disk is never sent to sockseek |
| **Reasons in words** | `reason=9` in sockseek's index means both "nobody had it" and "found and rejected". Every failure is written down the moment it happens with which of the two it was, worked out from what the run itself was asking, and with those conditions beside it. `--why` asks the network again about the tracks you name |
| **The screen, kept** | `logs/session_<date>.log` holds everything you saw, including the questions you were asked |

## How it works

The live count, the watchdog and the memory all rest on one thing: sockseek's `--on-complete`
runs a command as each track ends, and that command is an ordinary child process — so the
console stays sockseek's, with its own display and its own keys, while this tool reads a line
per finished track from a file. The alternative, reading `--progress-json` and typing into
sockseek's stdin, would have taken the terminal away and does not work anyway; the manual says
why in detail.

## How it is checked

```bash
./gradlew check
```

That is all of it: the unit tests and three checks a unit test cannot make, each driving the
finished jar as a process rather than a class. Everything is offline — no network, no
Soulseek account, no credentials anywhere.

**The whole command, against a recording.** It answers one question — did anything change
that nobody meant to change? The command runs end to end over a generated fixture of two
dozen playlists in three states of the output folder, and every line it prints is diffed
against a recorded answer. The fixture is deliberately nasty: Cyrillic and accented titles,
commas and ampersands and slashes, lengths written four different ways and sometimes
missing, duplicates, blank lines, titles wider than any console, index rows for files that
are not there. When a change is meant, `./gradlew test -Dgates.record=true` accepts the new
output and the diff goes into the commit, where it says in plain text what every playlist
will now see.

**The real binary.** sockseek has a mock mode — point it at a folder of files and it behaves
as though they were shared on Soulseek — so the live count, the record of what happened and
the skip that follows from it are exercised against the real thing, offline. This is the one
that surprises you: three of the measurements in the manual came out of it, and none of them
matched sockseek's own help text. It skips itself when the binary is not beside the project,
which is what happens in CI — sockseek is 114 MB and does not belong in a repository.

**The manuals.** Every option the tool accepts is documented, and nothing is documented that
the tool does not accept. This text is part of the interface.

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

Every line of this - the code, the tests, the gates and both manuals - was written and
tested by **Claude Opus 5** at high reasoning effort, directed by the repository owner.
No human has reviewed the code. What has been measured is dated; what has not is marked ⚠.

## License

MIT — see [LICENSE](LICENSE). Not affiliated with Soulseek or sockseek.
