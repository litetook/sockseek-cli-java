package dev.litetook.sockseek.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.litetook.sockseek.playlist.Entry;
import dev.litetook.sockseek.playlist.Playlists;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalInt;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * sockseek's {@code _index.csv}: what is here, what failed, and how it reads.
 *
 * <p>The two facts worth keeping in mind while
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
        assertEquals("FAILED  state=2 reason=9 (nobody had it, OR they did and the "
                        + "conditions refused it - explain_tracks says which)  "
                        + "Cosmic Drift - Pressure - Rivet (Cosmic Drift Rework) | 5:37",
                SockseekIndex.format(failure(
                        "Cosmic Drift - Pressure - Rivet (Cosmic Drift Rework) | 5:37",
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
        assertEquals("FAILED  state=2 reason=9 (nobody had it, OR they did and the "
                        + "conditions refused it - explain_tracks says which)  "
                        + "(no matching playlist line, index title: Some Title)",
                SockseekIndex.format(failure("", "Some Title", "2", "9")));
    }

    @Test
    void aTrackThatWasNeverSearchedIsNotCalledASearchFailure() {
        // state 0: left in the queue when the run was cut short - the owner's index had eleven
        // after the 10-minute cap killed run #0056. state 4: skipped by --skip-not-found.
        // Both used to print "unknown reason code", which reads as "we looked and found
        // nothing" (found by an independent review, 2026-10-17).
        assertTrue(SockseekIndex.format(failure("x", "t", "0", "0"))
                .contains("(never searched - the run ended before its turn)"));
        assertTrue(SockseekIndex.format(failure("x", "t", "4", "9"))
                .contains("(not searched - skipped as not found last time)"));
        // a real search failure still reports the reason, not the state
        assertTrue(SockseekIndex.format(failure("x", "t", "2", "9"))
                .contains("(nobody had it, OR they did and the conditions refused it - explain_tracks says which)"));
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
            // a kilobyte, not ten bytes: an all-but-empty file no longer counts as a
            // track that arrived (SMALLEST_REAL_FILE)
            Files.write(out.resolve(name), new byte[2048]);
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

    @Test
    void betterIsTheBitrateTheLadderSortsBy(@TempDir Path dir) throws IOException {
        // the playlist says 200 s, so bytes * 8 / 200 is the bitrate to within a few percent:
        // 128 kbps is 3.2 MB, 320 kbps is 8 MB. No file is opened to work that out
        OptionalInt len = OptionalInt.of(200);
        Path at128 = dir.resolve("a.mp3");
        Path at320 = dir.resolve("b.mp3");
        Path at192 = dir.resolve("c.mp3");
        Path flac = dir.resolve("a.flac");
        Files.write(at128, new byte[3_200_000]);
        Files.write(at320, new byte[8_000_000]);
        Files.write(at192, new byte[4_800_000]);
        Files.write(flac, new byte[25_000_000]);
        assertEquals(OptionalInt.of(128), Better.kbps(at128, len));
        assertEquals(OptionalInt.of(320), Better.kbps(at320, len));

        assertTrue(Better.worthReplacing(at320, at128, len), "320 beats 128");
        assertTrue(Better.worthReplacing(at192, at128, len), "192 beats 128");
        assertFalse(Better.worthReplacing(at128, at320, len), "128 never beats 320");
        assertFalse(Better.worthReplacing(at320, at320, len), "the same is not better");
        assertTrue(Better.worthReplacing(flac, at320, len), "lossless beats lossy");
        assertFalse(Better.worthReplacing(at320, flac, len), "lossy never beats lossless");
        assertTrue(Better.say(at320, at128, len).contains("128 -> 320 kbps"),
                Better.say(at320, at128, len));
    }

    @Test
    void aFileThatIsNotTheLengthThePlaylistClaimsIsNotCompared(@TempDir Path dir)
            throws IOException {
        // a ten-minute set standing in for a four-minute track divides by the wrong number
        // and comes out as a bitrate nothing real reaches; then the only safe answer is to
        // keep what is on disk
        OptionalInt len = OptionalInt.of(200);
        Path mine = dir.resolve("a.mp3");
        Path aSet = dir.resolve("b.mp3");
        Files.write(mine, new byte[3_200_000]);        // 128 kbps
        Files.write(aSet, new byte[90_000_000]);       // 3600 kbps for 200 s: not a bitrate
        assertEquals(OptionalInt.empty(), Better.kbps(aSet, len));
        assertFalse(Better.worthReplacing(aSet, mine, len));
        assertTrue(Better.say(aSet, mine, len).contains("not the length"),
                Better.say(aSet, mine, len));
    }

    @Test
    void withNoLengthInThePlaylistTheSizeIsAllThereIs(@TempDir Path dir) throws IOException {
        OptionalInt none = OptionalInt.empty();
        Path small = dir.resolve("a.mp3");
        Path big = dir.resolve("b.mp3");
        Path huge = dir.resolve("c.mp3");
        Files.write(small, new byte[100_000]);
        Files.write(big, new byte[130_000]);
        Files.write(huge, new byte[300_000]);
        assertTrue(Better.worthReplacing(big, small, none), "bigger, and plausibly the same");
        assertFalse(Better.worthReplacing(huge, small, none), "too big to be the same track");
        assertFalse(Better.worthReplacing(small, big, none));
    }
}
