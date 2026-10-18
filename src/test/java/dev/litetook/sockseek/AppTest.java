package dev.litetook.sockseek;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.litetook.sockseek.cli.Cli;
import dev.litetook.sockseek.config.Settings;
import dev.litetook.sockseek.deps.Downloader;
import dev.litetook.sockseek.index.Failures;
import dev.litetook.sockseek.index.Status;
import dev.litetook.sockseek.links.LinkReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.io.TempDir;

/**
 * The front end: what someone sees and what the engine is handed.
 *
 * <p>The questions, the lines and the engine are injected rather than read from a console:
 * what is tested is the behaviour someone sees, not how it is printed. Nothing here reaches
 * the network - the downloader throws if anything tries.
 */
class AppTest {

    @TempDir
    Path home;

    /** Scripted answers; when they run out, input has ended. */
    private static final class Script implements App.Ask {
        private final Deque<String> answers = new ArrayDeque<>();
        final List<String> printed = new ArrayList<>();

        Script(String... said) {
            answers.addAll(List.of(said));
        }

        @Override
        public Optional<String> ask(String prompt) {
            printed.add(prompt);
            return Optional.ofNullable(answers.poll());
        }
    }

    /** The engine, remembering what it was asked to run. */
    private static final class Fake implements App.Engine {
        final List<List<String>> calls = new ArrayList<>();
        int code;

        @Override
        public int run(List<String> argv) {
            calls.add(argv);
            return code;
        }
    }

    private final Fake engine = new Fake();

    /** A folder that has been through a first run: the three files and a sockseek binary. */
    private Path ready() throws IOException {
        Settings.ensureFiles(home, line -> { });
        Files.createDirectories(home.resolve("Playlists"));
        Files.writeString(home.resolve("sockseek.exe"), "", StandardCharsets.UTF_8);
        return home;
    }

    private Path playlist(String name, String... lines) throws IOException {
        Path file = home.resolve("Playlists").resolve(name + ".txt");
        Files.createDirectories(file.getParent());
        Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        return file;
    }

    private App.Wiring wiring() {
        return new App.Wiring(home, engine, new LinkReader(), url -> {
            throw new IOException("a test must not reach the network");
        });
    }

    private record Run(int code, List<String> printed) {
        boolean said(String fragment) {
            return printed.stream().anyMatch(line -> line.contains(fragment));
        }
    }

    private Run run(List<String> args, String... answers) {
        Script script = new Script(answers);
        int code = App.run(args.toArray(String[]::new), script, script.printed::add, wiring());
        return new Run(code, script.printed);
    }

    // ------------------------------------------------------------------ the opening menu

    @Test
    void theMenuOffersThreeWaysIn() throws IOException {
        ready();
        assertEquals(List.of(
                "Where from?",
                "",
                "  1  download a playlist from a .txt file",
                "  2  save a .txt from a Spotify link, then download it",
                "  3  save a .txt from a SoundCloud link, then download it",
                "",
                "Pick one (Enter quits): "), run(List.of()).printed());
    }

    @Test
    void enterQuitsAndSoDoesTheEndOfInput() {
        assertEquals(Optional.empty(), App.sourceMenu(new Script(""), line -> { }));
        assertEquals(Optional.empty(), App.sourceMenu(new Script(), line -> { }));
    }

    @Test
    void aNumberPicksItsSource() {
        assertEquals(Optional.of(App.Source.TXT), App.sourceMenu(new Script("1"), line -> { }));
        assertEquals(Optional.of(App.Source.SPOTIFY),
                App.sourceMenu(new Script("2"), line -> { }));
        assertEquals(Optional.of(App.Source.SOUNDCLOUD),
                App.sourceMenu(new Script("soundcloud"), line -> { }));
    }

    @Test
    void aWrongAnswerAsksAgainInsteadOfGuessing() throws IOException {
        ready();
        assertTrue(run(List.of(), "7", "x", "").said("Type 1, 2 or 3, or Enter to quit."));
    }

    // ------------------------------------------------------------------ the table and the list

    @Test
    void theTableLinesUpTheColumns() throws IOException {
        ready();
        playlist("mix", "Artist - One | 3:01", "Artist - Two | 4:02");
        playlist("a-much-longer-name", "Artist - Three");
        assertEquals(List.of(
                "  #  playlist            tracks  here  missing",
                "  -  ------------------  ------  ----  -------",
                "  1  a-much-longer-name       1     0        1",
                "  2  mix                      2     0        2"),
                App.formatTable(App.scan(home.resolve("Playlists"), home.resolve("output"))));
    }

    @Test
    void anEmojiInANameDoesNotThrowTheColumnsOut() {
        List<App.Info> infos = List.of(
                new App.Info("🎧mix", Path.of("a.txt"), 1, empty(), List.of()),
                new App.Info("plain-name", Path.of("b.txt"), 1, empty(), List.of()));
        List<String> lines = App.formatTable(infos);
        // a surrogate pair is one character to the person, so both rows must end alike
        assertEquals(points(lines.get(2)), points(lines.get(3)), lines.toString());
    }

    @Test
    void theMissingListNumbersTheLinesAsTheyWereWritten() throws IOException {
        ready();
        playlist("mix", "Artist - One | 3:01", "Artist - Two | 4:02");
        App.Info info = App.scan(home.resolve("Playlists"), home.resolve("output")).get(0);
        assertEquals(List.of(
                "mix: 2 tracks, 0 here, 2 missing",
                "",
                "Missing:",
                "   1  Artist - One | 3:01",
                "   2  Artist - Two | 4:02"), App.formatMissing(info));
    }

    @Test
    void anEmptyPlaylistHasNothingToDo() throws IOException {
        ready();
        playlist("empty", "");
        Run run = run(List.of("--only", "empty"));
        assertEquals(0, run.code());
        assertTrue(run.said("Nothing to do."), run.printed().toString());
        assertEquals(List.of(), engine.calls);
    }

    // ------------------------------------------------------------------ the choice

    @Test
    void theChoiceUnderstandsTheAnswersThePromptOffers() {
        assertEquals(Optional.of(List.of(0)), App.parseChoice("1", 3));
        assertEquals(Optional.of(List.of(0, 2)), App.parseChoice("1,3", 3));
        assertEquals(Optional.of(List.of(1, 0)), App.parseChoice("2 1", 3));
        assertEquals(Optional.of(List.of(0)), App.parseChoice("1,1", 3));
        assertEquals(Optional.of(List.of(0, 1, 2)), App.parseChoice("all", 3));
        assertEquals(Optional.of(List.of(0, 1, 2)), App.parseChoice("*", 3));
        assertEquals(Optional.of(List.of()), App.parseChoice("   ", 3));
        assertEquals(Optional.of(List.of(0)), App.parseChoice(" 1 ", 3));
    }

    @Test
    void anAnswerOutOfRangeIsRefusedWhole() {
        assertEquals(Optional.empty(), App.parseChoice("0", 3));
        assertEquals(Optional.empty(), App.parseChoice("4", 3));
        assertEquals(Optional.empty(), App.parseChoice("1,4", 3));
        assertEquals(Optional.empty(), App.parseChoice("9999999999", 3));
        assertEquals(Optional.empty(), App.parseChoice("x", 3));
        assertEquals(Optional.empty(), App.parseChoice("²", 3));   // a superscript two
    }

    @Test
    void aChoiceThatMakesNoSenseAsksAgain() throws IOException {
        ready();
        playlist("mix", "Artist - One");
        Run run = run(List.of(), "1", "9", "");
        assertEquals(0, run.code());
        assertTrue(run.said("Type a number from 1 to 1, several separated by commas, or all."),
                run.printed().toString());
        assertEquals(List.of(), engine.calls);
    }

    // ------------------------------------------------------------------ the step back

    @Test
    void bAtTheTableGoesBackToTheSourceMenu() throws IOException {
        ready();
        playlist("mix", "Artist - One");
        Run run = run(List.of(), "1", "b", "");
        assertEquals(0, run.code());
        // the menu is printed twice: once at the start, once after the step back
        assertEquals(2, run.printed().stream().filter(line -> line.equals("Where from?")).count(),
                run.printed().toString());
    }

    @Test
    void bAtTheLinkGoesBackToTheSourceMenu() throws IOException {
        ready();
        Run run = run(List.of(), "2", "b", "");
        assertEquals(0, run.code());
        assertEquals(2, run.printed().stream().filter(line -> line.equals("Where from?")).count(),
                run.printed().toString());
        assertTrue(run.said("Paste the Spotify link ([b] back, Enter cancels): "),
                run.printed().toString());
    }

    @Test
    void backIsOfferedInThePromptItself() throws IOException {
        ready();
        playlist("mix", "Artist - One");
        assertTrue(run(List.of(), "1", "").said("[b] back"));
    }

    @Test
    void enterStillQuitsRatherThanSteppingBack() throws IOException {
        ready();
        playlist("mix", "Artist - One");
        Run run = run(List.of(), "1", "");
        assertEquals(0, run.code());
        assertEquals(1, run.printed().stream().filter(line -> line.equals("Where from?")).count(),
                run.printed().toString());
        assertEquals(List.of(), engine.calls);
    }

    // ------------------------------------------------------------------ the download

    @Test
    void theEngineStartsOnlyAfterAYes() throws IOException {
        ready();
        playlist("mix", "Artist - One", "Artist - Two");
        Run run = run(List.of("--only", "mix"), "y");
        assertEquals(0, run.code());
        assertEquals(List.of(List.of("--only", "mix")), engine.calls);
        assertTrue(run.said("Download these 2? [y] yes  [n] no: "), run.printed().toString());
    }

    @Test
    void noLeavesThePlaylistAlone() throws IOException {
        ready();
        playlist("mix", "Artist - One");
        Run run = run(List.of("--only", "mix"), "n");
        assertEquals(0, run.code());
        assertTrue(run.said("Skipped."), run.printed().toString());
        assertEquals(List.of(), engine.calls);
    }

    @Test
    void yesSkipsTheQuestionSoAScheduledRunNeedsNobody() throws IOException {
        ready();
        playlist("mix", "Artist - One");
        int code = App.run(new String[] {"--only", "mix", "--yes"}, null, line -> { }, wiring());
        assertEquals(0, code);
        assertEquals(List.of(List.of("--only", "mix")), engine.calls);
    }

    @Test
    void everythingElseGoesToTheEngineUntouched() throws IOException {
        ready();
        playlist("mix", "Artist - One");
        Path output = home.resolve("elsewhere");
        App.run(new String[] {"--only", "mix", "--yes", "--dry-run", "--length-tol", "60",
                "--output", output.toString()}, null, line -> { }, wiring());
        assertEquals(List.of(List.of("--only", "mix", "--dry-run", "--length-tol", "60",
                "--output", output.toString())), engine.calls);
    }

    @Test
    void interruptedStopsTheWholeSessionNotJustOnePlaylist() throws IOException {
        ready();
        playlist("one", "Artist - One");
        playlist("two", "Artist - Two");
        engine.code = 130;
        Run run = run(List.of(), "1", "all", "y", "y");
        assertEquals(130, run.code());
        assertEquals(1, engine.calls.size(), engine.calls.toString());
        assertTrue(run.said("Interrupted."), run.printed().toString());
    }

    @Test
    void onePlaylistBlowingUpDoesNotTakeTheOthersWithIt() throws IOException {
        ready();
        playlist("one", "Artist - One");
        playlist("two", "Artist - Two");
        List<List<String>> calls = new ArrayList<>();
        App.Engine exploding = argv -> {
            calls.add(argv);
            if (argv.contains("one")) {
                // what a bug inside the engine looks like from here: not an exit code, a
                // throw. Today's csv crash was exactly this shape.
                throw new IllegalStateException("something inside the engine broke");
            }
            return 0;
        };
        Script script = new Script("1", "all", "y", "y");
        int code = App.run(new String[0], script, script.printed::add,
                new App.Wiring(home, exploding, new LinkReader(), url -> {
                    throw new IOException("a test must not reach the network");
                }));
        assertEquals(2, calls.size(), "the second playlist never ran: " + calls);
        assertEquals(1, code, script.printed.toString());
        assertTrue(script.printed.stream().anyMatch(line -> line.contains("ERROR")),
                script.printed.toString());
    }

    @Test
    void theWorstExitCodeOfTheRunsIsTheExitCode() throws IOException {
        ready();
        playlist("mix", "Artist - One");
        engine.code = 1;
        assertEquals(1, run(List.of("--only", "mix"), "y").code());
    }

    // ------------------------------------------------------------------ the dead ends

    @Test
    void anUnknownPlaylistNameIsASetupProblem() throws IOException {
        ready();
        playlist("mix", "Artist - One");
        Run run = run(List.of("--only", "nope"));
        assertEquals(2, run.code());
        assertTrue(run.said("No playlist named 'nope' in "), run.printed().toString());
    }

    @Test
    void anEmptyFolderSaysSoInsteadOfShowingAnEmptyMenu() throws IOException {
        ready();
        Run run = run(List.of(), "1");
        assertEquals(2, run.code());
        assertTrue(run.said("No playlist .txt files in "), run.printed().toString());
    }

    @Test
    void theFirstRunAsksForTheLoginAndStops() {
        Run run = run(List.of());
        assertEquals(2, run.code());
        assertTrue(run.said("Put your Soulseek login in "), run.printed().toString());
        assertTrue(run.said("Then run this again. Nothing can be downloaded until then."),
                run.printed().toString());
        // and the file it asks for is NOT written for you: a placeholder file looks filled in
        assertFalse(Files.exists(home.resolve(Settings.LOCAL_CONFIG_NAME)));
        assertTrue(Files.isRegularFile(home.resolve(Settings.CONFIG_NAME)));
    }

    @Test
    void aFlagWithoutItsValueIsRefusedBeforeAnythingHappens() {
        Run run = run(List.of("--only"));
        assertEquals(2, run.code());
        assertTrue(run.said("argument --only: expected one argument"),
                run.printed().toString());
        assertFalse(Files.exists(home.resolve(Settings.CONFIG_NAME)));
    }

    // ------------------------------------------------------------------ the one-shot options

    @Test
    void versionIsPrintedAndNothingElseHappens() {
        Run run = run(List.of("--version"));
        assertEquals(0, run.code());
        assertEquals(List.of("sockseek-cli " + Cli.VERSION), run.printed());
        assertFalse(Files.exists(home.resolve(Settings.CONFIG_NAME)));
    }

    @Test
    void helpNamesTheLauncherOfThisPlatform() {
        Run run = run(List.of("--help"));
        assertEquals(0, run.code());
        assertEquals("The one command of this tool.", run.printed().get(0));
        assertTrue(run.said("    " + App.LAUNCHER), run.printed().toString());
        assertFalse(run.said("py run.py"), run.printed().toString());
    }

    @Test
    void importFromAFileWritesThePlaylistAndStopsThere() throws IOException {
        ready();
        Path source = home.resolve("exported.m3u");
        Files.writeString(source, """
                #EXTM3U
                #EXTINF:181,Artist - One
                One.mp3
                #EXTINF:242,Artist - Two
                Two.mp3
                """, StandardCharsets.UTF_8);
        Run run = run(List.of("--import", source.toString(), "--as", "imported"));
        assertEquals(0, run.code());
        assertTrue(Files.isRegularFile(home.resolve("Playlists").resolve("imported.txt")));
        assertTrue(run.said("imported.txt: 2 track(s) from exported.m3u"),
                run.printed().toString());
        assertTrue(run.said("Now run: " + App.LAUNCHER + " --only imported"),
                run.printed().toString());
        assertEquals(List.of(), engine.calls);
    }

    @Test
    void importRefusesToReplaceAPlaylistWithoutBeingTold() throws IOException {
        ready();
        playlist("imported", "Artist - Mine");
        Path source = home.resolve("exported.m3u");
        Files.writeString(source, "#EXTM3U\n#EXTINF:181,Artist - One\nOne.mp3\n",
                StandardCharsets.UTF_8);
        Run run = run(List.of("--import", source.toString(), "--as", "imported"));
        assertEquals(2, run.code());
        assertTrue(run.said("Pass --overwrite to replace the existing playlist."),
                run.printed().toString());
        assertEquals("Artist - Mine\n",
                Files.readString(home.resolve("Playlists").resolve("imported.txt")));
    }

    @Test
    void aLinkThisToolCannotReadIsSaidPlainly() throws IOException {
        ready();
        Run run = run(List.of("--import", "https://example.com/some/list"));
        assertEquals(2, run.code());
        assertTrue(run.said("Not a link this tool can read: https://example.com/some/list"),
                run.printed().toString());
        assertTrue(run.said("Spotify and SoundCloud playlists are supported."),
                run.printed().toString());
    }

    // ------------------------------------------------------- one question, and the progress

    @Test
    void severalPlaylistsAreAskedAboutOnceForAllOfThem() throws IOException {
        ready();
        playlist("one", "Artist - A", "Artist - B");
        playlist("two", "Artist - C");
        Run run = run(List.of(), "1", "all", "y");
        assertEquals(0, run.code());
        assertEquals(List.of(List.of("--only", "one"), List.of("--only", "two")), engine.calls);
        assertEquals(1, run.printed().stream()
                .filter(line -> line.startsWith("Download these")).count(),
                run.printed().toString());
        assertTrue(run.said("Download these 3? [y] yes  [n] no: "), run.printed().toString());
    }

    @Test
    void thePriceIsShownBeforeTheOnlyYes() throws IOException {
        ready();
        playlist("one", "Artist - A", "Artist - B");
        playlist("two", "Artist - C");
        Run run = run(List.of(), "1", "all", "n");
        assertTrue(run.said("To download: 2 playlist(s), 3 missing track(s)."),
                run.printed().toString());
        assertTrue(run.said("34 per 220 s"), run.printed().toString());
        // three tracks in two playlists: both open with a full budget, so nothing is waited for
        assertTrue(run.said("adds about 0 min in all"), run.printed().toString());
        assertEquals(List.of(), engine.calls);
    }

    @Test
    void noNowStopsTheWholeSetRatherThanOnePlaylist() throws IOException {
        ready();
        playlist("one", "Artist - A");
        playlist("two", "Artist - C");
        Run run = run(List.of(), "1", "all", "n");
        assertEquals(0, run.code());
        assertTrue(run.said("Skipped."), run.printed().toString());
        assertEquals(List.of(), engine.calls);
    }

    @Test
    void everyPlaylistReportsItsShareAndTheRunningTotal() throws IOException {
        ready();
        playlist("one", "Artist - A", "Artist - B");
        playlist("two", "Artist - C");
        Run run = run(List.of(), "1", "all", "y");
        // the fake engine downloads nothing, so nothing arrives - what is tested is that the
        // line is there, numbered, and counts against the whole set
        assertTrue(run.said("[1/2] one: 0 of 2 arrived - 0 of 3 in all"),
                run.printed().toString());
        assertTrue(run.said("[2/2] two: 0 of 1 arrived - 0 of 3 in all"),
                run.printed().toString());
    }

    private static int searchMinutes0(int tracks) {
        return App.searchMinutes(tracks, 34, 220);
    }

    @Test
    void theSearchMinutesCountWindowsRatherThanSearches() {
        // the first window is free: a playlist opens with a full budget and waits for nothing
        assertEquals(0, searchMinutes0(1));
        assertEquals(0, searchMinutes0(34));
        assertEquals(4, searchMinutes0(35));     // one window waited out: 220 s
        assertEquals(4, searchMinutes0(68));
        assertEquals(8, searchMinutes0(69));     // two windows: 440 s
    }

    @Test
    void allAndYesAskNothingAtAll() throws IOException {
        // the unattended night run: no source menu, no "which one", no confirmation
        ready();
        playlist("one", "Artist - A", "Artist - B");
        playlist("two", "Artist - C");
        Script script = new Script();   // no answers at all: anything asked would end the run
        int code = App.run(new String[] {"--all", "--yes"}, script, script.printed::add,
                wiring());
        assertEquals(0, code);
        assertEquals(List.of(List.of("--only", "one"), List.of("--only", "two")), engine.calls);
        assertEquals(List.of(), script.printed.stream()
                .filter(line -> line.endsWith(": ")).toList());   // nothing was a prompt
    }

    @Test
    void allStillShowsWhatItIsAboutToDo() throws IOException {
        ready();
        playlist("one", "Artist - A", "Artist - B");
        Run run = run(List.of("--all"), "y");
        assertTrue(run.said("Playlists:"), run.printed().toString());
        assertTrue(run.said("To download: 1 playlist(s), 2 missing track(s)."),
                run.printed().toString());
    }

    @Test
    void onlyWinsOverAllAndSaysSo() throws IOException {
        ready();
        playlist("one", "Artist - A");
        playlist("two", "Artist - C");
        Run run = run(List.of("--all", "--only", "two", "--yes"));
        assertEquals(List.of(List.of("--only", "two")), engine.calls);
        assertTrue(run.said("--only wins"), run.printed().toString());
    }

    @Test
    void thePriceIsThePriceOfTheRunTheCacheWillActuallyMake() throws IOException {
        // the one number the person approves must be for the run that happens. The cache is
        // read by the engine, which answers with an exit code - by then the yes is given
        ready();
        playlist("one", "Artist - A", "Artist - B", "Artist - C");
        // "len=45" is what the shipped config asks under, and a row is only acted on by
        // a run asking the same way - otherwise the price would promise a skip the run
        // would not make
        Files.writeString(home.resolve(Failures.FILE_NAME), String.join(TAB,
                LocalDateTime.now().toString(), Failures.CONFIRMED_ABSENT, "1", "len=45",
                "one", "Artist - A")
                + NEWLINE);
        Run run = run(List.of(), "1", "1", "n");
        assertTrue(run.said("3 missing track(s). 1 of them were searched for recently and "
                + "nobody had them, so 2 will be searched."), run.printed().toString());
        assertTrue(run.said("Download these 2?"), run.printed().toString());
    }

    @Test
    void aMemoryFromOtherConditionsDoesNotChangeThePrice() throws IOException {
        // the filter that refused it yesterday is off today, so yesterday's "nothing came
        // back" says nothing about today - and the price must not pretend otherwise
        ready();
        playlist("one", "Artist - A", "Artist - B");
        Files.writeString(home.resolve(Failures.FILE_NAME), String.join(TAB,
                LocalDateTime.now().toString(), Failures.CONFIRMED_ABSENT, "1",
                "len=45,min-bitrate=256", "one", "Artist - A") + NEWLINE);
        Run run = run(List.of(), "1", "1", "n");
        assertTrue(run.said("To download: 1 playlist(s), 2 missing track(s)."),
                run.printed().toString());
        assertTrue(!run.said("will be searched"), run.printed().toString());
    }

    @Test
    void anExpiredMemoryDoesNotChangeThePrice() throws IOException {
        ready();
        playlist("one", "Artist - A", "Artist - B");
        Files.writeString(home.resolve(Failures.FILE_NAME), String.join(TAB,
                LocalDateTime.now().minusDays(3).toString(), Failures.CONFIRMED_ABSENT, "1",
                "len=45", "one", "Artist - A") + NEWLINE);
        Run run = run(List.of(), "1", "1", "n");
        assertTrue(run.said("To download: 1 playlist(s), 2 missing track(s)."),
                run.printed().toString());
        assertTrue(!run.said("will be searched"), run.printed().toString());
    }

    @Test
    void noCacheMakesThePriceTheWholeThing() throws IOException {
        ready();
        playlist("one", "Artist - A", "Artist - B");
        Files.writeString(home.resolve(Failures.FILE_NAME), String.join(TAB,
                LocalDateTime.now().toString(), Failures.CONFIRMED_ABSENT, "1", "one",
                "Artist - A")
                + NEWLINE);
        Run run = run(List.of("--no-cache"), "1", "1", "n");
        assertTrue(!run.said("will be searched"), run.printed().toString());
    }

    // ------------------------------------------------------------------ the search brake

    @Test
    void theFirstPlaylistNeverWaits() {
        List<String> said = new ArrayList<>();
        Pace pace = new Pace(34, 220, said::add);
        long before = System.nanoTime();
        pace.waitTurn();
        assertTrue((System.nanoTime() - before) / 1_000_000 < 500);
        assertEquals(List.of(), said);
    }

    @Test
    void aPlaylistThatSpentSearchesMakesTheNextOneWait() {
        List<String> said = new ArrayList<>();
        // 2 searches of a 4-per-window budget = half the window, and a window is 220 s
        Pace pace = new Pace(4, 220, said::add);
        pace.waitTurn();
        pace.spent(2);
        Thread.currentThread().interrupt();   // so the wait ends at once instead of in 110 s
        pace.waitTurn();
        Thread.interrupted();                 // clear it again for the rest of the suite
        assertEquals(1, said.size(), said.toString());
        assertTrue(said.get(0).startsWith("pausing 1"), said.get(0));
        assertTrue(said.get(0).contains("4 searches per 220 s"), said.get(0));
    }

    @Test
    void zeroTurnsTheBrakeOff() {
        List<String> said = new ArrayList<>();
        Pace pace = new Pace(0, 220, said::add);
        pace.waitTurn();
        pace.spent(1000);
        pace.waitTurn();
        assertEquals(List.of(), said);
    }

    @Test
    void theSearchesCountedAreTheOnesThatReallyHappened(@TempDir Path dir) throws IOException {
        // the owner cancelled a 146-track playlist a third of the way through and the brake
        // charged him 581 s for queries that were never sent. What was asked for is the list;
        // what was spent is the events, one line per track that reached an end
        Path list = dir.resolve("mix.list.txt");
        Path events = dir.resolve("mix.events.txt");
        Files.writeString(list, """
                s:"title=A"
                s:"title=B"
                s:"title=C"

                """);
        Files.writeString(events, """
                1;Succeeded;None
                2;Failed;NoSearchResults
                """);
        assertEquals(2, Pace.searchesOf(list, events));
        // no events at all: the watcher never got going, so the list is the only estimate,
        // and erring high waits too long rather than risking the ban
        assertEquals(3, Pace.searchesOf(list, dir.resolve("none.txt")));
        assertEquals(0, Pace.searchesOf(dir.resolve("gone.txt"), dir.resolve("none.txt")));
    }

    private static final String TAB = String.valueOf((char) 9);
    private static final String NEWLINE = System.lineSeparator();

    private static Status empty() {
        return new Status(List.of(), List.of(), Map.of());
    }

    private static int points(String line) {
        return line.codePointCount(0, line.length());
    }
}
