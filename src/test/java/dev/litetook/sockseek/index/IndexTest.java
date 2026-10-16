package dev.litetook.sockseek.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.litetook.sockseek.playlist.Entry;
import dev.litetook.sockseek.playlist.Playlists;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * sockseek's {@code _index.csv}: what is here, what failed, and how it reads.
 *
 * <p>Ported from the Python tool's test_index.py. The two facts worth keeping in mind while
 * reading: state 3 means "was already there" and counts as success, and reason 9 does not mean
 * "not on the network" - run #0004 found two 320 kbps files for a row that said 9.
 */
class IndexTest {

    private static final String HEADER =
            "filepath,artist,album,title,length,tracktype,state,failurereason\n";

    private static List<Entry> entries(String text) {
        return Playlists.convert(text).entries();
    }

    /** A row as the tests care about it: everything else is empty. */
    private static IndexRow row(String title, String length, String state, String reason) {
        return new IndexRow("", "", "", title, length, "0", state, reason);
    }

    // ------------------------------------------------------------------ reading the file

    @Test
    void aCommaInsideATitleStaysInsideTheTitle(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("_index.csv");
        Files.writeString(file, HEADER + ",,,\"Hello, World\",200,0,2,9\n");
        List<IndexRow> rows = SockseekIndex.read(List.of(file));
        assertEquals("Hello, World", rows.get(0).title());
        assertEquals("2", rows.get(0).state());
    }

    @Test
    void eachRowIsPairedWithTheFolderItsPathIsRelativeTo(@TempDir Path dir) throws IOException {
        for (String sub : List.of("one", "two")) {
            Files.createDirectory(dir.resolve(sub));
            Files.writeString(dir.resolve(sub).resolve("_index.csv"),
                    HEADER + sub + ".mp3,,,T " + sub + ",60,0,1,0\n");
        }
        List<Located> located = SockseekIndex.readWithFolders(
                List.of(dir.resolve("one").resolve("_index.csv"),
                        dir.resolve("two").resolve("_index.csv")));
        assertEquals(List.of("one.mp3", "two.mp3"),
                located.stream().map(one -> one.row().filepath()).toList());
        assertEquals(List.of(dir.resolve("one"), dir.resolve("two")),
                located.stream().map(Located::folder).toList());
    }

    @Test
    void anUnreadableIndexIsSkippedRatherThanFatal(@TempDir Path dir) throws IOException {
        Path broken = dir.resolve("_index.csv");
        Files.write(broken, new byte[] {(byte) 0xff, (byte) 0xfe, (byte) 0x80});
        assertEquals(List.of(), SockseekIndex.read(List.of(broken)));
        assertEquals(List.of(), SockseekIndex.read(List.of(dir.resolve("nothing-here.csv"))));
    }

    // ------------------------------------------------------------------ failures

    @Test
    void onlyRowsThatAreNotOkCountAsFailuresAndTheyFindTheirLine() {
        List<Entry> entries = entries("A - One | 1:00\nB - Two | 2:00\n");
        List<Failure> failures = SockseekIndex.failures(entries, List.of(
                row("B - Two", "120", "2", "9"),
                row("A - One", "60", "1", "0")));
        assertEquals(List.of(new Failure("B - Two | 2:00", "B - Two", "120", "2", "9")),
                failures);
    }

    @Test
    void theStateColumnIsJudgedDirectly() {
        // 1 downloaded, 3 already present on a re-run: both fine. 2 and anything unknown: not
        List<Entry> entries = entries(
                "A - One | 1:00\nB - Two | 1:00\nC - Three | 1:00\nD - Four | 1:00\n");
        List<IndexRow> rows = List.of(
                row("A - One", "60", "1", "0"), row("B - Two", "60", "2", "0"),
                row("C - Three", "60", "3", "0"), row("D - Four", "60", "7", "0"));
        assertEquals(List.of("B - Two | 1:00", "D - Four | 1:00"),
                SockseekIndex.failures(entries, rows).stream().map(Failure::playlistLine).toList());
    }

    @Test
    void aFailureThatMatchesNoLineLeavesTheLineEmpty() {
        // one row against three entries: counts differ, so no pairing by position
        List<Entry> entries = entries("A - One | 1:00\nB - Two | 2:00\nC - Three | 3:00\n");
        Failure failure = SockseekIndex.failures(entries,
                List.of(row("something else", "5", "2", "9"))).get(0);
        assertEquals("", failure.playlistLine());
        assertEquals("something else", failure.indexTitle());
    }

    // ------------------------------------------------------------------ how a failure reads

    private static Failure failure(String line, String title, String state, String reason) {
        return new Failure(line, title, "337", state, reason);
    }

    @Test
    void aMatchedFailureShowsStateReasonAndTheLine() {
        assertEquals("FAILED  state=2 reason=9 (no candidate passed the name/length/quality "
                        + "conditions)  Funk Tribu - September - Cry For You (Funk Tribu Rework)"
                        + " | 5:37",
                SockseekIndex.format(failure(
                        "Funk Tribu - September - Cry For You (Funk Tribu Rework) | 5:37",
                        "Cry For You", "2", "9")));
    }

    @Test
    void theReasonCodeIsSpelledOutSoItIsNotMisread() {
        // reason=9 does NOT mean "nobody has it": reading it literally is the mistake this
        // wording exists to prevent
        assertTrue(SockseekIndex.format(failure("x", "t", "2", "9")).contains("conditions"));
        assertTrue(SockseekIndex.format(failure("x", "t", "2", "4"))
                .contains("did not come through"));
        assertTrue(SockseekIndex.format(failure("x", "t", "2", "7"))
                .contains("unknown reason code"));
    }

    @Test
    void anUnmatchedFailureSaysSoInsteadOfGuessing() {
        assertEquals("FAILED  state=2 reason=9 (no candidate passed the name/length/quality "
                        + "conditions)  (no matching playlist line, index title: Some Title)",
                SockseekIndex.format(failure("", "Some Title", "2", "9")));
    }

    @Test
    void aMissingStateOrReasonShowsAsAQuestionMark() {
        assertTrue(SockseekIndex.format(failure("x", "t", "", ""))
                .startsWith("FAILED  state=? reason=? (unknown reason code)  "));
    }

    // ------------------------------------------------------------------ what is already here

    private static final String LINES = "A - One | 1:00\nB - Two | 2:00\n";

    private Status status(Path out, List<String> rows, String... files) throws IOException {
        if (!rows.isEmpty()) {
            Files.writeString(out.resolve("_index.csv"),
                    HEADER + String.join("\n", rows) + "\n");
        }
        for (String name : files) {
            Files.write(out.resolve(name), "xxxxxxxxxx".getBytes(StandardCharsets.UTF_8));
        }
        return SockseekIndex.playlistStatus(entries(LINES), out);
    }

    private static List<String> lines(List<Entry> entries) {
        return entries.stream().map(Entry::original).toList();
    }

    @Test
    void aStrayQuoteInTheIndexStillCountsTheTrackAsHere(@TempDir Path out) throws IOException {
        // A quoted field holding a quote that is not doubled is malformed CSV, and it reaches
        // this file through any text sockseek writes - an album or a title from Spotify or
        // SoundCloud, where a name like Say "Hello" is ordinary. (Not through the file name:
        // Windows forbids a quote there.) Until 2026-10-16 this threw an UncheckedIOException
        // that escaped the whole front end, so NO playlist could be listed at all. Dropping
        // the index instead would be just as wrong: every downloaded track would read as
        // missing and be searched again.
        Status status = status(out,
                List.of("./one.mp3,,\"Album \"live\"\",A - One,60,0,1,0"),
                "one.mp3");
        assertEquals(List.of("A - One | 1:00"), lines(status.have()));
        assertEquals(List.of("B - Two | 2:00"), lines(status.missing()));
    }

    @Test
    void noIndexAtAllMeansEverythingIsMissing(@TempDir Path out) throws IOException {
        Status status = status(out, List.of());
        assertEquals(List.of("A - One | 1:00", "B - Two | 2:00"), lines(status.missing()));
        assertEquals(List.of(), status.have());
    }

    @Test
    void aRowWithItsFileOnDiskIsHere(@TempDir Path out) throws IOException {
        Status status = status(out, List.of("one.mp3,,,A - One,60,0,1,0"), "one.mp3");
        assertEquals(List.of("A - One | 1:00"), lines(status.have()));
        assertEquals(List.of("B - Two | 2:00"), lines(status.missing()));
        assertEquals("one.mp3", status.files().get("A - One | 1:00").getFileName().toString());
    }

    @Test
    void aRowWhoseFileWasDeletedIsMissingAgain(@TempDir Path out) throws IOException {
        // the whole point of looking at the disk: the index alone would call this one done
        Status status = status(out, List.of("one.mp3,,,A - One,60,0,1,0"));
        assertEquals(List.of(), status.have());
        assertEquals(2, status.missing().size());
    }

    @Test
    void stateThreeCountsTooBecauseItMeansAlreadyPresent(@TempDir Path out) throws IOException {
        Status status = status(out, List.of("one.mp3,,,A - One,60,0,3,0"), "one.mp3");
        assertEquals(List.of("A - One | 1:00"), lines(status.have()));
    }

    @Test
    void aFailedRowIsMissingEvenWhenAFileOfThatNameExists(@TempDir Path out) throws IOException {
        Status status = status(out, List.of(",,,A - One,60,0,2,9"), "one.mp3");
        assertEquals(List.of(), status.have());
    }

    @Test
    void aRowWhoseArtistWasSplitOffStillFindsItsLine(@TempDir Path out) throws IOException {
        // --extract-artist writes the title in two columns; matching puts them back together
        Status status = status(out, List.of("one.mp3,A,,One,60,0,1,0"), "one.mp3");
        assertEquals(List.of("A - One | 1:00"), lines(status.have()));
    }
}
