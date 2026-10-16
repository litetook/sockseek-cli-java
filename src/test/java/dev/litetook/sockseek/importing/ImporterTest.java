package dev.litetook.sockseek.importing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.litetook.sockseek.playlist.Playlist;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Turning an exported list (m3u, csv/tsv, plain text) into a playlist file. */
class ImporterTest {

    private Imported read(Path dir, String name, String text) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, text);
        return new Importer().read(file);
    }

    private static boolean said(Imported found, String part) {
        return found.warnings().stream().anyMatch(warning -> warning.contains(part));
    }

    // ------------------------------------------------------------------ m3u

    @Test
    void extinfLinesBecomePlaylistLines(@TempDir Path dir) throws IOException {
        Imported found = read(dir, "list.m3u", """
                #EXTM3U
                #EXTINF:249,Alpha Band - First Song
                C:\\music\\first.mp3
                #EXTINF:3875,Beta Crew - Long One
                C:\\music\\long.flac
                """);
        assertEquals(List.of("Alpha Band - First Song | 4:09", "Beta Crew - Long One | 64:35"),
                found.lines());
        assertEquals("m3u", found.source());
        assertEquals(List.of(), found.warnings());
    }

    @Test
    void anUnknownLengthLeavesTheLineWithoutOne(@TempDir Path dir) throws IOException {
        // -1 is what players write when they do not know the duration
        Imported found = read(dir, "list.m3u",
                "#EXTM3U\n#EXTINF:-1,Alpha Band - First Song\npath.mp3\n");
        assertEquals(List.of("Alpha Band - First Song"), found.lines());
    }

    @Test
    void filePathsAndOtherTagsAreIgnored(@TempDir Path dir) throws IOException {
        Imported found = read(dir, "list.m3u",
                "#EXTM3U\n#PLAYLIST:My Set\n#EXTINF:60,A - One\nA.mp3\nB.mp3\n");
        assertEquals(List.of("A - One | 1:00"), found.lines());
    }

    @Test
    void anEntryWithoutATitleIsNamedInAWarning(@TempDir Path dir) throws IOException {
        Imported found = read(dir, "list.m3u",
                "#EXTM3U\n#EXTINF:60,\nA.mp3\n#EXTINF:60,A - One\nB.mp3\n");
        assertEquals(List.of("A - One | 1:00"), found.lines());
        assertTrue(said(found, "without a title"), found.warnings().toString());
    }

    @Test
    void aFileThatIsNotM3uSaysSo(@TempDir Path dir) throws IOException {
        Imported found = read(dir, "list.m3u", "#EXTM3U\nnothing useful here\n");
        assertEquals(List.of(), found.lines());
        assertTrue(said(found, "no #EXTINF"), found.warnings().toString());
    }

    @Test
    void theFormatIsRecognisedByContentToo(@TempDir Path dir) throws IOException {
        Imported found = read(dir, "whatever.txt", "#EXTM3U\n#EXTINF:60,A - One\nA.mp3\n");
        assertEquals("m3u", found.source());
        assertEquals(List.of("A - One | 1:00"), found.lines());
    }

    // ------------------------------------------------------------------ csv and tsv

    @Test
    void aSpotifyStyleExport(@TempDir Path dir) throws IOException {
        Imported found = read(dir, "list.csv", """
                Track Name,Artist Name(s),Album Name,Duration (ms)
                First Song,Alpha Band,Some Album,249000
                Second Song,"Beta Crew, Gamma Duo",Other,200500
                """);
        assertEquals(List.of("Alpha Band - First Song | 4:09",
                "Beta Crew, Gamma Duo - Second Song | 3:21"), found.lines());
        assertEquals("table", found.source());
    }

    @Test
    void aTabSeparatedExportWithMinuteTimes(@TempDir Path dir) throws IOException {
        Imported found = read(dir, "list.tsv", "Artist\tTitle\tTime\nAlpha Band\tFirst Song\t4:09\n");
        assertEquals(List.of("Alpha Band - First Song | 4:09"), found.lines());
    }

    @Test
    void secondsAreTakenAsSeconds(@TempDir Path dir) throws IOException {
        Imported found = read(dir, "list.csv", "title,artist,duration\nFirst Song,Alpha Band,249\n");
        assertEquals(List.of("Alpha Band - First Song | 4:09"), found.lines());
    }

    @Test
    void millisecondsAreRecognisedWithoutAHintInTheColumnName(@TempDir Path dir)
            throws IOException {
        // 249000 cannot be seconds: that would be a 69-hour track
        Imported found = read(dir, "list.csv",
                "title,artist,duration\nFirst Song,Alpha Band,249000\n");
        assertEquals(List.of("Alpha Band - First Song | 4:09"), found.lines());
    }

    @Test
    void aLongDjSetInSecondsStaysInSeconds(@TempDir Path dir) throws IOException {
        Imported found = read(dir, "list.csv", "title,artist,duration\nLong Set,Alpha Band,3875\n");
        assertEquals(List.of("Alpha Band - Long Set | 64:35"), found.lines());
    }

    @Test
    void aMissingDurationColumnIsAWarningNotAFailure(@TempDir Path dir) throws IOException {
        Imported found = read(dir, "list.csv", "Track Name,Artist\nFirst Song,Alpha Band\n");
        assertEquals(List.of("Alpha Band - First Song"), found.lines());
        assertTrue(said(found, "no duration column"), found.warnings().toString());
    }

    @Test
    void noTitleColumnMeansNothingIsImported(@TempDir Path dir) throws IOException {
        Imported found = read(dir, "list.csv", "bpm,key\n128,8A\n");
        assertEquals(List.of(), found.lines());
        assertTrue(said(found, "no title column"), found.warnings().toString());
    }

    @Test
    void aRowWithoutATitleIsSkippedWithItsNumber(@TempDir Path dir) throws IOException {
        Imported found = read(dir, "list.csv",
                "title,artist,duration\n,Alpha Band,100\nFirst Song,Alpha Band,249\n");
        assertEquals(List.of("Alpha Band - First Song | 4:09"), found.lines());
        assertTrue(said(found, "row 2"), found.warnings().toString());
    }

    @Test
    void aTrackWithoutAnArtistKeepsJustTheTitle(@TempDir Path dir) throws IOException {
        Imported found = read(dir, "list.csv", "title,duration\nFirst Song,249\n");
        assertEquals(List.of("First Song | 4:09"), found.lines());
    }

    // ------------------------------------------------------------------ plain text

    @Test
    void namesPassThroughAndCommentsDrop(@TempDir Path dir) throws IOException {
        Imported found = read(dir, "list.txt",
                "Alpha Band - First Song\n# a note\n\n  Beta Crew - Second  Song \n");
        assertEquals(List.of("Alpha Band - First Song", "Beta Crew - Second Song"),
                found.lines());
        assertEquals("text", found.source());
    }

    @Test
    void ourOwnPlaylistSurvivesARoundTrip(@TempDir Path dir) throws IOException {
        Imported found = read(dir, "list.txt", "Alpha Band - First Song | 4:09\n");
        assertEquals(List.of("Alpha Band - First Song | 4:09"), found.lines());
    }

    @Test
    void anEmptyFileSaysSo(@TempDir Path dir) throws IOException {
        Imported found = read(dir, "list.txt", "\n\n");
        assertEquals(List.of(), found.lines());
        assertFalse(found.warnings().isEmpty());
    }

    // ------------------------------------------------------------------ writing the playlist

    private Path source(Path dir) throws IOException {
        Path source = dir.resolve("export.m3u");
        Files.writeString(source, "#EXTM3U\n#EXTINF:249,Alpha Band - First Song\nA.mp3\n");
        return source;
    }

    @Test
    void thePlaylistIsWrittenAndNamedAfterTheFile(@TempDir Path dir) throws IOException {
        Path playlists = dir.resolve("Playlists");
        ImportResult result = Imports.importFile(source(dir), playlists, null, false);
        assertEquals(Optional.of(playlists.resolve("export.txt")), result.written());
        assertEquals("Alpha Band - First Song | 4:09\n",
                Files.readString(result.written().orElseThrow()));
        assertEquals(List.of("Alpha Band - First Song | 4:09"), result.found().lines());
    }

    @Test
    void theNameCanBeChosen(@TempDir Path dir) throws IOException {
        ImportResult result = Imports.importFile(source(dir), dir.resolve("Playlists"),
                "my-set", false);
        assertEquals("my-set.txt", result.written().orElseThrow().getFileName().toString());
    }

    @Test
    void anExistingPlaylistIsNotReplacedSilently(@TempDir Path dir) throws IOException {
        Path playlists = dir.resolve("Playlists");
        Files.createDirectory(playlists);
        Files.writeString(playlists.resolve("export.txt"), "hand written\n");
        ImportResult result = Imports.importFile(source(dir), playlists, null, false);
        assertEquals(Optional.empty(), result.written());
        assertTrue(said(result.found(), "already exists"), result.found().warnings().toString());
        assertEquals("hand written\n", Files.readString(playlists.resolve("export.txt")));

        ImportResult forced = Imports.importFile(source(dir), playlists, null, true);
        assertEquals("Alpha Band - First Song | 4:09\n",
                Files.readString(forced.written().orElseThrow()));
    }

    @Test
    void anImportThatFoundNothingWritesNothing(@TempDir Path dir) throws IOException {
        Path empty = dir.resolve("empty.m3u");
        Files.writeString(empty, "#EXTM3U\n");
        Path playlists = dir.resolve("Playlists");
        assertEquals(Optional.empty(), Imports.importFile(empty, playlists, null, false).written());
        assertFalse(Files.exists(playlists));
    }

    @Test
    void whatWasWrittenIsAPlaylistTheEngineCanRead(@TempDir Path dir) throws IOException {
        Path written = Imports.importFile(source(dir), dir.resolve("Playlists"), null, false)
                .written().orElseThrow();
        Playlist playlist = Playlist.fromFile(written);
        assertEquals(List.of(), playlist.warnings());
        assertEquals(List.of(OptionalInt.of(249)),
                playlist.entries().stream().map(entry -> entry.track().length()).toList());
        assertEquals("s:\"title=Alpha Band - First Song, length=249\"",
                playlist.entries().get(0).listLine());
    }

    @Test
    void aQuoteInsideAQuotedFieldDoesNotStopTheImport(@TempDir Path dir) throws IOException {
        // exactly what Exportify wrote in the owner's own export (2026-10-16): the label
        // field carries quotes that are not doubled, which is malformed CSV. The Python
        // tool reads such a file, so this one has to as well - it used to die with an
        // UncheckedIOException from commons-csv instead.
        Imported found = read(dir, "liked.csv",
                "Track Name,Artist Name(s),Duration (ms),Record Label\n"
                + "Fortepiano,Khrystyna Soloviy,246760,\"ТОВ \"Супериор Мьюзик\"\"\n"
                + "Second,Someone,120000,Label\n");
        assertEquals(List.of("Khrystyna Soloviy - Fortepiano | 4:07",
                "Someone - Second | 2:00"), found.lines());
        assertTrue(said(found, "is not doubled"), found.warnings().toString());
    }

    @Test
    void aWellFormedTableIsStillReadStrictly(@TempDir Path dir) throws IOException {
        // the lenient second attempt must not become the normal path: a good file gets no
        // warning about quotes
        Imported found = read(dir, "ok.csv",
                "Track Name,Artist Name(s),Duration (ms)\nOne,Someone,60000\n");
        assertEquals(List.of("Someone - One | 1:00"), found.lines());
        assertEquals(List.of(), found.warnings());
    }
}
