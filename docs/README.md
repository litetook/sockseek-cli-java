# sockseek-cli — the manual

Downloads playlists from Soulseek by driving [sockseek](https://github.com/fiso64/sockseek).

You keep plain text files like `Artist - Title | 4:09`. The tool shows how much of each
playlist is already on disk, searches **only what is missing**, and tells you what did not
arrive and why. Re-running the same playlist tomorrow is not a workaround — it is how the tool
is meant to be used, because what you can get depends on who is online right now.

This manual is in two voices. **What it's for** is what you need to decide something.
**Behind the scenes** is what actually happens, and why a default is the number it is — skip
it until something surprises you.

---

## Quick start

```bash
run.cmd                 # Windows:     pick a playlist, see what is missing, confirm
./run.sh                # macOS, Linux: the same
```

The first run creates three config files and stops, because it cannot download anything until
it knows who you are:

```
Put your Soulseek login in ...\sockseek.local.conf:
    username = your-login
    password = your-password
```

Then put `.txt` files into `Playlists/` (or import one, below) and run it again.

You need **a JDK 21**. Nothing else: the launcher builds the jar if it is missing, and
sockseek and yt-dlp are fetched on demand at pinned versions, each checked against its sha256
before it is put in place. Nothing is installed system-wide and nothing needs admin rights.

---

## What a run looks like

```
Playlists:

  #  playlist              tracks  here  missing
  -  --------------------  ------  ----  -------
  1  techno-hard        107    72       35
  2  house               84    84        0

What to download? (1, or 1,2, or all; [b] back, Enter quits): all

techno-hard: 107 tracks, 72 here, 35 missing

Missing:
   1  Cosmic Drift - Pressure Rivet | 5:37
   ...

house: 84 tracks, 84 here, 0 missing
Nothing to do.

To download: 1 playlist(s), 35 missing track(s).
That is about 35 search(es); sockseek is allowed 34 per 220 s, so searching alone takes
about 4 min. Downloads come on top.

Download these 35? [y] yes  [n] no: y

[1/1] techno-hard: searching 35 missing track(s)
...
>> 12 of 35 done, 9 downloaded
>> 13 of 35 done, 9 downloaded  (NoSearchResults: Some Track (Edit) | 3:12)
...
[1/1] techno-hard: 28 of 35 arrived - 28 of 35 in all
```

**One question for the whole set.** You see every missing list first, then the price of the
lot, then one `y`. Choosing `all` at midnight does not mean answering a prompt per playlist
all night.

**`>>` lines are the live count**, and they are this tool's, not sockseek's. sockseek's own
display knows the playlist it is running; these know the run.

**`here` means two things at once**: sockseek's index says the track arrived *and* the file is
really on disk. Delete the file and it is missing again. File names alone cannot carry this —
they come from whoever uploaded them, and look like
`... (Clean) 1A 142 [www.dj-promo.org].mp3`.

---

## Playlist files

One `.txt` per playlist in `Playlists/`. The file name is the playlist name and becomes the
output folder name. One track per line:

```
Artist - Title | 4:09
Some Track (Remix) | 5:37
# a comment; blank lines are ignored too
```

The part after `|` is the length — `m:ss`, `h:mm:ss` or plain seconds. A line without one is
searched without a length filter. Duplicate lines collapse into one and the log says how many
were dropped. Files are read as UTF-8; something else is decoded as cp1251, then latin-1, with
a warning each time — fix the file rather than rely on it.

### Importing one

```bash
run.cmd --import "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M"
run.cmd --import "https://soundcloud.com/bonobo/sets/outlier-radio-on-nts" --as outlier
run.cmd --import exported.csv --as techno-2026 --overwrite
```

`--import` writes the playlist file and stops. Downloading stays a separate, confirmed step.

**Behind the scenes.** Spotify is read from its public embed page, whose HTML carries the track
list as JSON — no account, no API key, no Premium, all of which sockseek's own Spotify support
needs. That page seems to stop at 100 tracks, so an import that returns exactly 100 warns you.
SoundCloud is not one of sockseek's input types, so `yt-dlp` resolves it track by track; a
private set needs `--cookies-from-browser chrome` (you log in there, never here). A SoundCloud
title that already reads `Artist - Title` is kept; otherwise the uploader is prefixed, which is
a guess.

---

## Settings: `config.yaml`

There are two config files and the split is the whole point:

| File | Holds | Read by |
|---|---|---|
| `config.yaml` | folders, and the numbers this tool decides itself | this tool |
| `sockseek.conf` | the Soulseek login and every sockseek option | sockseek |

Both files carry a one-line comment per key, so you can work in them without leaving them;
this manual has the why and the measurements behind each default. Precedence is
**command line > config.yaml > built-in default**, and every run log opens with the effective
value of each key and where it came from, so "why is it this number?" is always answered by
the log rather than by guessing.

This is what a fresh folder gets, comments and all:

```yaml
paths:
  # the sockseek binary; .exe is added or dropped to match what is really there
  sockseek: sockseek.exe
  # folder of playlist .txt files - this is the input
  playlists: Playlists
  # where the audio goes, one sub-folder per playlist
  output: output

search:
  # HARD filter: a shared file may differ from the playlist length by this many
  # seconds, -1 turns it off
  length_tol_s: 45
  # how many times to attempt one playlist; each attempt is a fresh sockseek
  retries: 1
  # wall clock for ONE attempt at one playlist, in minutes, 0 = no limit
  playlist_timeout_min: 60
  # stop a playlist when nothing has finished AND nothing has arrived on disk for
  # this many seconds, 0 = never stop it
  stall_timeout_s: 300
  # do not search again for a track nobody was sharing, if that was within this
  # many hours; 0 = always search. Fractions are fine
  skip_not_found_hours: 2
  # the brake across playlists: all of them together stay under this many searches
  # per 220 s, or the Soulseek server bans the account for 30 minutes
  searches_per_window: 34
  # remember in outcomes.tsv how each track ended; false ignores the file entirely
  use_cache: true
```

### `paths.sockseek`, `paths.playlists`, `paths.output`

**What they're for.** Where the binary is, where your playlists are, where the music goes.
A relative path is relative to the folder you run from; an absolute one (`output: D:\music`)
is taken as it is.

**Behind the scenes.** The binary is accepted with or without `.exe`, whichever is actually
there, so one config file works on Windows and Linux.

### `length_tol_s` — 45 seconds · `--length-tol`

**What it's for.** How far a shared file's length may differ from the length in your playlist
before it is refused. This is a **hard** filter, the only one besides the bitrate floor. `-1`
turns it off.

**Behind the scenes.** sockseek's own default is 3 seconds, which rejects the very file you
asked for: playlist lengths — SoundCloud ones especially — run systematically shorter than the
copies people share. Measured 2026-10-07: 319 s in the list against 346 s on the network, same
remix. A wide window does not let the *wrong* song in, because Soulseek requires every word of
the query to appear in the file path and the query still carries the `(... Remix)` tag; across
13 candidates on 5 tracks, not one wrong version came back. And do not reach for sockseek's
`--strict-title`: measured on a full 30-track playlist it let 6 tracks through where the plain
window let 15.

### `retries` — 1 · `--retries`

**What it's for.** How many times a playlist is attempted. A second attempt re-searches only
what is still missing.

**Behind the scenes.** It genuinely earns tracks — on run #0002 an uploader that timed out on
the first attempt delivered on the second — but it doubles the wall clock, and each attempt is
a **new sockseek process with a fresh search budget**, which raises the real risk of the
30-minute server ban. That is why the shipped file asks for one.

### `playlist_timeout_min` — 10 minutes · `--playlist-timeout`

**What it's for.** A wall clock on one attempt at one playlist. `0` means none. **Minutes**,
and **per attempt**: with 40 and `retries: 2`, a playlist can run 80 minutes.

**Behind the scenes.** It is a limit on the whole sockseek process, never on one track. When it
fires, the process tree is killed and the log says so in a sentence that stands out — because
sockseek paints its progress display in place and nothing repaints it after the kill, so its
last frame sits there frozen, looking exactly like a hang. (It was read as one on 2026-10-17.)

### `stall_timeout_s` — 300 seconds · `--stall-timeout`

**What it's for.** Stop a playlist that has stopped getting anywhere. If no track has finished
— downloaded, failed, skipped, anything — for this many seconds, the run is not stuck behind a
slow track, it is stuck. `0` turns it off.

**Behind the scenes.** This is the honest half of "а щоб трек не провисав". The tool hears
about every finished track the instant it finishes (see *How the live count works*), so a
silence is a real signal rather than a guess. 300 s sits above the longest **normal** pause,
which is the 220-second search window — waiting for that window is not a hang, and nothing
here tries to shorten it. What this is **not** is a deadline for one track: there is no way to
cancel a single sockseek job from outside, so the tool does not pretend to have one.

### `use_cache` — true · `--no-cache`

**What it's for.** The master switch for `outcomes.tsv`. `false` makes the file not exist for
the length of a run: nothing is read from it, nothing is written back. A clean sweep that
leaves no trace, the way `--dry-run` leaves none.

**Behind the scenes.** Not the same as `skip_not_found_hours: 0`, which only stops the
**skipping** — the run still counts what happened and writes it down. `use_cache: false` also
stops the counting, so a run you did for a one-off reason cannot push a track's "failed 4
runs running" counter up and make it look worse than it is.

### `searches_per_window` — 34 · `--searches-per-window`

**What it's for.** The brake across playlists. It keeps the searches of **all** of them
together under this many per 220 seconds, by pausing between playlists. `0` turns it off.

**Behind the scenes.** sockseek has the same budget, but it lives inside **one process**, and
this tool runs one process per playlist. Every new playlist therefore starts with a full
budget while the Soulseek server has been counting the whole time — so a row of small
playlists can put seventy searches on the server in two minutes, with every process entirely
within its own rules, and the account is banned for 30 minutes.

Nothing had noticed while playlists were picked one at a time by hand, with minutes between
them. `--all` made it real on the first night it existed.

A playlist earns the time its searches cost — `searches / 34 × 220` seconds — and if it
finished sooner the next one waits for the difference. A big playlist usually takes longer
than it earned and waits for nothing. What is counted is the lines actually handed to
sockseek, not the missing tracks: the cache holds some of those back, and waiting for a
search that was never made would be waiting for nothing.

The cost is worth saying plainly: 672 missing tracks cannot be searched in under
`672 / 34 × 220` seconds, about 72 minutes, however they are split up. Being faster than that
was never speed — it was borrowing against a ban.

A dry run and a `--preview` ask the server for nothing, so neither is paced.

### `skip_not_found_hours` — 2 hours · `--skip-not-found-hours`

**What it's for.** Do not spend this hour's searches on a track nobody had an hour ago. `0`
searches everything again, every time. Fractions work: `0.5` is half an hour.

**Behind the scenes.** Searching is the scarce thing, not bandwidth: sockseek is allowed 34
searches per 220 s, so a 107-track playlist spends most of its night waiting for that window.
Only **one** class of failure is ever **skipped** — `NoSearchResults`, nobody shared the file.
"Found, but the upload never came through" is never skipped, because those are precisely the
ones that arrive next time. A run that skips anything says so out loud:

```
skipping 4 track(s) nobody had within the last 2 hour(s); set skip_not_found_hours: 0 to
search them anyway
```

**Two hours, and why not a day.** It was a day until 2026-10-17, counted in calendar dates,
and the owner found the hole by using the tool: "деякі треки просто з'явились у пошуку коли я
в інший час додаток запустив". Who is sharing changes through the day — a track that was
nowhere on 6 October arrived on the 7th as a 893 kbps flac — so a morning miss must not
silence a track until midnight. Two hours is still enough that a re-run five minutes later
does not spend the search budget on the same hopeless lines.

This does **not** use sockseek's own `--skip-not-found`, for two measured reasons: it remembers
for ever rather than for a day, and the index it reads cannot tell the two classes apart —
`reason=9` is written both for "nobody had it" and for "found and rejected by the conditions".

---

## `outcomes.tsv`: what happened last time

Every failure is written down; only one class is ever acted on. The file sits next to the
config, one tab-separated line per track:

```
2026-10-17T12:24:31  AllDownloadsFailed  4  techno-hard  Artist - Title | 4:09
2026-10-17T18:02:10  NoSearchResults     1  chill        Other - Track | 3:12
       when               class       runs      playlist           playlist line
```

**What it's for.** Two jobs that must not be confused:

- **Skipping** — `NoSearchResults` only, and only within `skip_not_found_hours`. That is the
  one above.
- **Counting** — everything else is remembered and **never** skipped, so a track that has
  been failing the same way for three runs can say so:

  ```
  3 track(s) have ended the same way 3+ runs in a row - see outcomes.tsv
    x4  AllDownloadsFailed  Blue Kettle - Lantern Drift | 5:23
  ```

**Behind the scenes.** The classes are sockseek's own words as a track ends, through
`--on-complete`, not codes out of the index — `reason=9` covers both "nobody had it" and
"found and rejected by the conditions", and cannot be split. Measured pairs:

| word | index row | means |
|---|---|---|
| `NoSearchResults` | `state=2 reason=9` | nobody had it |
| `AllDownloadsFailed` | `state=2 reason=4` | found, the upload died |

A track that arrives is **forgotten outright** — it is on disk now, and the folder is a better
record than any file of ours. A track that fails in a *new* way starts counting from one,
because a different cause is a different story.

This record is the only place the history survives at all: a run log knows one run, and
sockseek rewrites `_index.csv` in place on the next one.

---

## How the live count works

**What it's for.** You see progress while it happens, and the failure cache knows the real
reason each track failed.

**Behind the scenes.** sockseek can run a command when a job ends — `--on-complete` — so the
tool asks it to append one line per track to `work/<playlist>.events.txt`:

```
3;Failed;NoSearchResults
1;Succeeded;None
```

Three machine-safe fields: the **line number in the list file this run wrote**, the state, and
the reason as a word. The row is what makes the join exact — no matching by title, nothing a
track called `Iron / Tölva - T.N.T. & more` can break.

Two things make this the right mechanism rather than a clever one:

- **The console stays sockseek's.** The action is a child process of sockseek, so its progress
  display and its `t` / `c` / `i` keys keep working. The alternative considered first —
  reading `--progress-json` and typing into sockseek's stdin — would have taken the terminal
  away, and does not work anyway: those keys need a real console, and the JSON stream names a
  track in one event and identifies it by a GUID in another, with no field joining them.
- **The reason is a word, not a code.** `NoSearchResults` versus the index's ambiguous
  `reason=9`. Without it the cache above could not exist.

One thing it cannot see: a track that sockseek itself skips via `--skip-not-found` fires no
action at all, whatever its help says (measured 2026-10-17). This tool does not use that flag.

---

## Settings: `sockseek.conf`

Same folder. The keys are sockseek's own command-line options without the `--`, same units;
`sockseek.exe --help` is the reference. A typo is loud: sockseek rejects an unknown key on
startup. The file shipped below is what a fresh folder gets, comments and all.

```
# sockseek's own options, without the leading --. No secrets here: this file is
# safe to share. The login goes in sockseek.local.conf, which overrides any key
# below as well. `sockseek.exe --help` is the full list.
#
# These two stay commented out: sockseek refuses a key with an empty value.
# username =
# password =

# --- how long to wait on a source that is not delivering ---
# milliseconds with no bytes arriving before this candidate is dropped
max-stale-time = 12000
# how many candidates one track may be tried from before it is given up
max-retries = 3
# an uploader that fails once drops down the ranking
fails-to-downrank = 1
# an uploader that fails twice is left out for the rest of the run
fails-to-ignore = 2

# --- quality ---
# the ONLY hard floor: a file below this bitrate is refused outright
min-bitrate = 256
# everything with pref- only RANKS candidates and never refuses one
pref-min-bitrate = 320
# 1411 kbps is CD lossless; anything above it is 24-bit or hi-res, so it ranks
# last and arrives only when there is nothing cheaper
pref-max-bitrate = 1411
# flac and mp3 equally preferred; wav is not listed, so it ranks below both
pref-format = flac,mp3

# --- picking a source early ---
# take the first candidate that satisfies the preferred conditions instead of
# waiting for the whole search to finish
fast-search = true
# but spend this many milliseconds collecting candidates first
fast-search-delay = 2000
# a pass mark for that shortcut ONLY; a slower uploader stays in the normal set.
# The unit is undocumented and still unproven - 1 is sockseek's own default
fast-search-min-up-speed = 200
# prefer files whose path contains the track title / the artist
pref-strict-title = true
pref-strict-artist = true
```

### The waiting keys — "three tries, then somebody else"

**What they're for.** A source that is not delivering should be dropped quickly and replaced,
and a source that keeps failing should stop being offered.

- `max-stale-time = 12000` — milliseconds with no bytes before a candidate is dropped.
- `max-retries = 3` — how many candidates one track may be tried from.
- `fails-to-downrank = 1`, `fails-to-ignore = 2` — an uploader drops down the ranking after one
  failure and out of it after two.

**Behind the scenes.** There is **no** "slower than 60 kB/s" lever anywhere in sockseek. It is
not a condition (`uploadspeed`, `upspeed` and `speed` are all rejected as unknown), and one
transfer cannot be interrupted from outside without taking the console away. So the rule is
enforced with sockseek's own clock instead of ours: 12 s of silence rather than 30, three
candidates rather than ten, and a bad uploader out of the running for the rest of the run.
Moving to the next candidate is something sockseek already does by itself — measured with
three copies of one track and two forced failures. `Attempt: 1/1` in a log does not mean
retries are broken; it means there was only one candidate to begin with.

30 s was the old value, and 5 minutes was tried before that: run #0026 spent 25 minutes on 15
tracks with a 5-minute window and brought back **zero**. Those uploaders do not deliver at all,
they are not merely slow — a long window only makes each failure expensive.

### The quality keys

**What they're for.** A floor you will not go below, and preferences that order everything else.

**Behind the scenes.** `min-bitrate = 256` is the only hard floor. There is deliberately **no**
hard maximum: an expensive file should merely come last, not be refused. `pref-*` keys only
rank, they never reject, so the two ceilings cost you nothing in coverage:

- `pref-max-bitrate = 1411` is exactly CD lossless, so anything above it can only be 24-bit or
  above 48 kHz. Measured on 57 downloaded files: the heaviest was a flac at 2722 kbps — 102 MB
  for 5:15 — and the six files above 1411 kbps held **26% of all the bytes**.
- `pref-format = flac,mp3` leaves wav out on purpose: a CD wav costs about 10 MB a minute where
  a flac of the same CD quality costs 7. The list is unordered — this says "flac or mp3 are
  both fine, wav is not what we are after", nothing more.

Do **not** enable `strict-conditions`. Together with `min-bitrate` it discards every share from
the standard Soulseek client, which does not broadcast a bitrate at all — which is also why a
file can occasionally slip in below the floor.

### Picking a fast uploader early

`fast-search = true` takes the first candidate that satisfies the preferred conditions instead
of waiting for the whole search. `fast-search-delay = 2000` collects candidates for two seconds
first (sockseek's default of 300 ms often means "whoever answered first"), and
`fast-search-min-up-speed = 200` is a pass mark for the shortcut only — a slower uploader stays
in the normal set and can still be chosen, so no track is lost to it. Set it to `1` to switch
it off.

⚠ **The unit of `fast-search-min-up-speed` is undocumented and still unproven.** It is not
MB/s — with the default of 1 a candidate reporting 0.02 MB/s was accepted. So 200 is either
200 kB/s, which skips exactly those, or 200 B/s, which changes nothing.

### The three layers

sockseek reads one config file and has no `include`, so the tool merges them itself into
`work/sockseek.effective.conf` and points sockseek at that:

```
sockseek.conf  <  sockseek.local.conf  <  --sockseek-conf FILE
```

Lowest to highest; a key replaces the same key below it, a new key is appended. `sockseek.conf`
holds **no secrets** and is safe to share; `sockseek.local.conf` holds your login and is
git-ignored, and it overrides any other key too, so it is also the place for a
machine-specific tweak. `--sockseek-conf FILE` adds a top layer for one run — a second account,
a lossless profile — without touching anything on disk.

> ⚠ The merged file in `work/` holds your login **in plain text**. `work/` is git-ignored and
> safe to delete at any time; just do not copy it anywhere.

Four keys must not be set here, because the tool passes them on every command line and yours
would be dead: `output-dir`, `length-tol`, `name-format`, `index-path`.

---

## Why a big playlist takes a night

The Soulseek server bans a user for **30 minutes** if too many searches arrive too quickly.
sockseek keeps itself under that: 34 searches per 220 seconds — one every 6.5 s. When the
budget runs out, tracks sit in `Waiting` and start as soon as the window renews. **That is not
a hang**, and nothing in this tool tries to hurry it.

Two consequences worth knowing:

- The budget lives **inside one sockseek process**, which is why there is one process per
  playlist and why you should not run two copies of the tool at once.
- Each retry is a new process with a fresh budget — so `retries: 2` really can put 45 searches
  on the server in six minutes.

A ban cannot be lifted from the client. You wait it out.

---

## When a track does not arrive

A missing track is news about Soulseek, not a bug: the run still ends with exit code `0` and
names every one of them.

```
FAILED  state=2 reason=9 (no candidate passed the name/length/quality conditions)  Quiet Harbour - Amber Jetty (Remix) | 5:19
```

**`reason=9` does not mean "not on the network."** It means nothing passed the conditions — run
#0004 found two 320 kbps copies of a track whose row said 9, rejected because their length was
27 s off.

So you can ask for the real answer with `--explain`, which searches once more per failed track
with every condition off:

```
  -> nothing on the network: 0 candidates even with every condition off
  -> IS on the network: 2 candidate(s), closest 346s/320kbps
     rejected by length: playlist says 319s, diff +27s
     to take it: run.cmd --only <playlist> --length-tol 28
  -> IS on the network: 1 candidate(s), closest 267s/320kbps
     length and quality are fine, so the uploader did not deliver - try again later
```

Three causes, three different things to do. It is **off by default** since 2026-10-17: each
explanation costs one more search out of the budget above, and on a big playlist that is the
difference between a long night and a longer one.

### Things to try, roughly in order

1. `--length-tol 60`, or `-1` to drop the length filter.
2. `--sockseek-args="--artist-maybe-wrong"` — free-form titles often put the artist elsewhere.
3. `--sockseek-args="--remove-brackets --remove-ft"` — drops `[FREE DOWNLOAD]` and `feat. ...`.
   Note that `--remove-brackets` only removes **square** brackets, so it does nothing for
   `Vertigo (Quarry Trance Edit)`.
4. `--sockseek-args="--desperate"` — broader, slower, noisier.
5. `--preview` — runs the search and prints what comes back, downloading nothing.
6. `--verbose` — sockseek's own log in `logs/sockseek_<run>_<name>.log`, the only place that
   tells `No search results` apart from "found, but the uploader sent nothing".

And then simply run the same playlist again tomorrow. Of 15 tracks still missing after run
#0017, twelve had **no candidate at all** even with every condition off. Nothing in the code
fixes that; another hour of the day might.

---

## Files the tool writes

| Path | What |
|---|---|
| `output/<name>/` | the audio, flat, named `Artist - Title.ext` |
| `output/<name>/_index.csv` | sockseek's index: the only link between a playlist line and a file |
| `outcomes.tsv` | how each track ended last time, with the time and a run counter |
| `logs/session_<stamp>.log` | **everything you saw on screen**, one file per start of the command |
| `logs/run_<NNNN>_<stamp>.log` | one run of one playlist; opens with `run #NNNN` and the effective config |
| `logs/sockseek_<NNNN>_<name>.log` | sockseek's own verbose log (only with `--verbose`) |
| `logs/run_seq.txt` | the run counter: one integer |
| `work/` | generated list files, event files and the merged conf; rebuilt every run, safe to delete |

**Session log vs run log.** The run log starts after all the choosing is over, which is exactly
the part that was missing when a session went strangely. The session log holds the whole thing
— menu, questions, answers, every run. What it does **not** hold is sockseek's own display:
that child writes straight to the console, past this tool. Use `--verbose` for that.

**Run numbers.** Every run gets one, so you can say "run 43" instead of quoting a timestamp.
It comes from `run_seq.txt`, is written back before anything is downloaded, and is rebuilt
from the log file names if that file is lost. One number per playlist: picking three playlists
uses three numbers.

---

## What this tool cannot do

Kept here on purpose, so nobody goes looking for a switch that does not exist.

- **No speed rule per track.** "Slower than 60 kB/s for 5 seconds, switch source" cannot be
  built: sockseek exposes no transfer rate to the outside and no way to interrupt one job.
  What exists instead is in *The waiting keys*.
- **No deadline per track.** Cancelling a single job needs sockseek's `c` key, which needs a
  real console. `stall_timeout_s` watches the whole playlist instead.
- **Nothing checks what was downloaded.** There is no ffprobe step (settled, 2026-10-16), so
  the only claim about a file is the one its uploader made. Three things follow, all measured:
  the `length` column of `_index.csv` is copied from your playlist and proves nothing; a file
  whose peer reports no length bypasses the length filter entirely (one file came out 49 s
  short and was a different edit); and `pref-min-bitrate` only ranks, so files below 320 do
  arrive — one at 83 kbps.
- **No `130` on Ctrl+C.** The JVM takes the signal and dies, so the run log gets no closing
  line. What `java -jar` finally returns has never been measured.

---

## Options

Front end: `--only NAME`, `--all`, `--yes`, `--import WHAT`, `--as NAME`, `--overwrite`,
`--cookies-from-browser BROWSER`, `--version`, `--help`. Everything else is passed through to
the engine untouched.

**The unattended run is `--all --yes`.** `--all` takes every playlist in the folder without
asking which, `--yes` skips the confirmation, and together they ask nothing at all — the run
still prints the table and the price, it just does not stop for an answer. `--yes` on its own
still asks which playlists, which is one keystroke too many for a scheduled night. If both
`--all` and `--only` are given, `--only` wins and the run says so.

Engine: `--playlists DIR`, `--output DIR`, `--sockseek FILE`, `--config FILE`,
`--sockseek-conf FILE`, `--length-tol SEC`, `--playlist-timeout MIN`, `--stall-timeout SEC`,
`--skip-not-found-hours HOURS`, `--no-cache`, `--searches-per-window N`, `--retries N`,
`--explain`,
`--preview`, `--dry-run`,
`--verbose`, `--sockseek-args "STRING"`.

`--no-explain` is still accepted and does nothing: explaining is off unless you ask for it.
`--sockseek-args` appends raw arguments to every sockseek command — the way to try a sockseek
option once without editing the conf. Anything in it reaches sockseek unchecked.

Exit codes: `0` the run worked (tracks may still be missing), `1` the tool failed, `2` a setup
problem — a bad option, a missing `sockseek.exe`, an unknown `--only` name.

---

## Tests and gates

```bash
./gradlew check       # the unit tests, this manual, and the whole command end to end
./gradlew mockGate    # the above against the real sockseek binary; needs it present
./gradlew record      # accept today's output as the new recording, after a deliberate change
```

All of it is offline: no network, no Soulseek account, no credentials anywhere. Each
check builds itself a temporary folder and works there.

The broad one runs the whole command over a generated fixture - two dozen playlists, in
three states of the output folder - and diffs every line against a recorded answer, so an
accidental change anywhere shows up as text. The fixture is generated rather than real on
purpose, and kept deliberately nasty: Cyrillic and accented titles, commas and ampersands
and slashes, lengths written four ways and sometimes missing, duplicates, titles wider than
a console, index rows for files that are not there.

The sharp one is sockseek's mock mode, which lets the live count, the outcome record and the
skip be exercised against the real binary, offline. Three of the measurements in this manual
came out of it, and none of them matched the help text.

Until 2026-10-17 the gate was the Python tool this was ported from, run side by side and
diffed line by line. That answered "is the port faithful?" and the answer was yes; once the
two deliberately diverged the gate started measuring its own ignore list instead. The Python
tool is legacy now.

Every line of this - the code, the tests, the gates and both manuals - was written and
tested by **Claude Opus 5** at high reasoning effort, directed by the repository owner.
No human has reviewed the code. What has been measured is dated; what has not is marked ⚠.
