package dev.litetook.sockseek.playlist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Playlist files: lines, lengths, encodings.
 *
 * <p>Ported from the Python tool's test_playlist.py and the first half of test_runner.py. Every
 * expectation here is one the Python version already holds, so a difference is a bug in this
 * port until proven otherwise.
 */
class PlaylistTest {

    private static int seconds(String text) {
        return Playlists.parseDuration(text).orElseThrow();
    }

    // ------------------------------------------------------------------ durations

    @Test
    void minutesAndSeconds() {
        assertEquals(249, seconds("4:09"));
    }

    @Test
    void hours() {
        assertEquals(3875, seconds("1:04:35"));
    }

    @Test
    void plainSeconds() {
        assertEquals(249, seconds("249"));
    }

    @Test
    void aFractionIsRoundedNotTruncated() {
        assertEquals(5, seconds("4.9"));
        assertEquals(4, seconds("4.4"));
        assertEquals(5, seconds("0:04.9"));
    }

    @Test
    void whatIsNotADuration() {
        // "4:" matters: Java's split drops a trailing empty field and Python's does not,
        // so without split(":", -1) this would quietly become 4 seconds
        for (String bad : List.of("", "abc", "4:", ":09", "1:2:3:4", "inf", "-5", "Infinity",
                "NaN")) {
            assertEquals(OptionalInt.empty(), Playlists.parseDuration(bad), bad);
        }
    }

    // ------------------------------------------------------------------ one line

    @Test
    void theWholeNameIsSearchedAsOneString() {
        assertEquals(Optional.of(new Track("", "Nova Blue - Kate Ryan - Song (Remix)",
                        OptionalInt.of(245))),
                Playlists.parseLine("Nova Blue - Kate Ryan - Song (Remix) | 4:05"));
    }

    @Test
    void blankLinesAndCommentsAreNotTracks() {
        assertEquals(Optional.empty(), Playlists.parseLine("   "));
        assertEquals(Optional.empty(), Playlists.parseLine("# comment | 1:00"));
    }

    @Test
    void anUploaderPrefixIsKept() {
        // proven 2026-10-07 (runs #0004/#0005): sockseek finds the same files with or without
        // the SoundCloud uploader prefix, so the line is never rewritten
        Track track = Playlists.parseLine(
                "Serafin - Katy Perry - Firework (Serafin Trance Edit) | 12:07").orElseThrow();
        assertEquals("Serafin - Katy Perry - Firework (Serafin Trance Edit)", track.title());
        assertEquals("", track.artist());
    }

    @Test
    void commasAndQuotesAreRemoved() {
        // a comma separates properties in a sockseek search string and a quote ends it
        assertEquals("Hello World", Playlists.parseLine("Hello, \"World\" | 2:00")
                .orElseThrow().title());
    }

    @Test
    void aLineWithoutALengthHasNone() {
        assertEquals(OptionalInt.empty(), Playlists.parseLine("A - Song").orElseThrow().length());
    }

    @Test
    void accentsSurvive() {
        assertEquals("Désenchantée",
                Playlists.parseLine("Désenchantée | 3:00").orElseThrow().title());
    }

    @Test
    void filesTheToolWroteItselfAreNotPlaylists() {
        assertTrue(Playlists.isGenerated(Path.of("tracks_x.txt")));
        assertTrue(Playlists.isGenerated(Path.of("mix.list.txt")));
        assertFalse(Playlists.isGenerated(Path.of("mix.txt")));
    }

    // ------------------------------------------------------------------ the sockseek line

    @Test
    void theListLineCarriesArtistTitleAndLength() {
        assertEquals("s:\"artist=A, title=T, length=249\"",
                Playlists.buildListLine(new Track("A", "T", OptionalInt.of(249))));
    }

    @Test
    void withoutALengthOrArtistOnlyTheTitleIsAsked() {
        assertEquals("s:\"title=T\"",
                Playlists.buildListLine(new Track("", "T", OptionalInt.empty())));
    }

    // ------------------------------------------------------------------ a whole file

    @Test
    void convertingCollectsWarnings() {
        Converted converted = Playlists.convert("A - One | 1:00\n# skip\n\nB - Two\n");
        assertEquals(2, converted.entries().size());
        Entry first = converted.entries().get(0);
        assertEquals("A - One | 1:00", first.original());
        assertEquals("s:\"title=A - One, length=60\"", first.listLine());
        assertEquals(new Track("", "A - One", OptionalInt.of(60)), first.track());
        assertEquals(1, converted.warnings().size());
        assertTrue(converted.warnings().get(0).contains("no valid length"),
                converted.warnings().toString());
    }

    @Test
    void duplicateLinesCollapseToOneEntry() {
        // the last line is spelled differently but asks sockseek for exactly the same thing
        Converted converted = Playlists.convert(
                "A - One | 1:00\nB - Two | 2:00\nA - One | 1:00\nA - One | 60\n");
        assertEquals(List.of("A - One | 1:00", "B - Two | 2:00"),
                converted.entries().stream().map(Entry::original).toList());
        assertTrue(converted.warnings().stream()
                        .anyMatch(warning -> warning.contains("2 duplicate line(s) dropped")),
                converted.warnings().toString());
    }

    // ------------------------------------------------------------------ encodings

    private Decoded read(Path dir, byte[] data) throws IOException {
        Path file = dir.resolve("x.txt");
        Files.write(file, data);
        return Playlists.read(file);
    }

    @Test
    void utf8WithAByteOrderMark(@TempDir Path dir) throws IOException {
        Decoded decoded = read(dir, ("\ufeffПривіт | 1:00").getBytes(StandardCharsets.UTF_8));
        assertEquals("Привіт | 1:00", decoded.text());
        assertEquals(Optional.empty(), decoded.warning());
    }

    @Test
    void theCp1251FallbackIsWordedAsAGuess(@TempDir Path dir) throws IOException {
        Decoded decoded = read(dir, "Привіт | 1:00".getBytes(Charset.forName("windows-1251")));
        assertEquals("Привіт | 1:00", decoded.text());
        String warning = decoded.warning().orElseThrow();
        assertTrue(warning.contains("not valid UTF-8"), warning);
        assertTrue(warning.contains("decoded as cp1251"), warning);
        assertTrue(warning.contains("check the file"), warning);
    }

    @Test
    void readingNeverFailsOnEncoding(@TempDir Path dir) throws IOException {
        // invalid in utf-8, unmapped in cp1251
        Decoded decoded = read(dir, new byte[] {(byte) 0x98, (byte) 0x98, 0x20});
        assertTrue(decoded.warning().orElseThrow().contains("latin-1"),
                decoded.warning().toString());
    }

    // ------------------------------------------------------------------ the folder

    @Test
    void aFolderGivesItsPlaylistsSortedAndWithoutGeneratedOnes(@TempDir Path dir)
            throws IOException {
        for (String name : List.of("b.txt", "a.txt", "tracks_x.txt", "mix.list.txt", "c.md")) {
            Files.writeString(dir.resolve(name), "A - One | 1:00\n");
        }
        assertEquals(List.of("a.txt", "b.txt"),
                Playlists.find(dir).stream().map(p -> p.getFileName().toString()).toList());
    }

    @Test
    void aPlaylistFileBecomesEntriesAndKeepsItsName(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("my-mix.txt");
        Files.writeString(file, "A - One | 1:00\nB - Two\n");
        Playlist playlist = Playlist.fromFile(file);
        assertEquals("my-mix", playlist.name());
        assertEquals(2, playlist.size());
        assertTrue(playlist.warnings().get(0).contains("no valid length"));
    }
}
