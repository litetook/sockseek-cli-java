package dev.litetook.sockseek.links;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.litetook.sockseek.process.ProcessRunner;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * A playlist link into playlist lines.
 *
 * <p>Nothing here touches the network: the page fetch and the yt-dlp call are injected, so what
 * is tested is our parsing of shapes recorded from the real services on 2026-10-12 - Spotify's
 * embed page carries the track list as JSON under
 * props.pageProps.state.data.entity.trackList, and yt-dlp prints one line per track.
 */
class LinkReaderTest {

    private static final String SPOTIFY_URL =
            "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M";
    private static final String SC_URL =
            "https://soundcloud.com/bonobo/sets/outlier-radio-on-nts";

    /** An embed page shaped like the real one: the JSON inside __NEXT_DATA__. */
    private static String embedPage(String tracks, String name) {
        return "<!DOCTYPE html><html><body>"
                + "<script id=\"__NEXT_DATA__\" type=\"application/json\">"
                + "{\"props\":{\"pageProps\":{\"state\":{\"data\":{\"entity\":{"
                + "\"name\":\"" + name + "\",\"type\":\"playlist\",\"trackList\":[" + tracks
                + "]}}}}}}"
                + "</script></body></html>";
    }

    private static String track(String title, String subtitle, long durationMs) {
        return "{\"title\":\"" + title + "\",\"subtitle\":\"" + subtitle + "\",\"duration\":"
                + durationMs + ",\"uri\":\"spotify:track:x\"}";
    }

    private static Linked spotify(String page, String url) {
        return new LinkReader(any -> page, null).read(url, null, null);
    }

    private static boolean said(Linked linked, String part) {
        return linked.warnings().stream().anyMatch(warning -> warning.contains(part));
    }

    // ------------------------------------------------------------------ what counts as a link

    @Test
    void theShapesTheServicesActuallyHandOut() {
        Map<String, String> expected = new java.util.LinkedHashMap<>();
        expected.put(SPOTIFY_URL, "spotify");
        expected.put("https://open.spotify.com/playlist/abc?si=9f2&pt=x", "spotify");
        expected.put("https://open.spotify.com/intl-de/playlist/abc", "spotify");
        expected.put("https://open.spotify.com/album/abc", "spotify");
        expected.put("spotify:playlist:abc", "spotify");
        expected.put(SC_URL, "soundcloud");
        expected.put("https://m.soundcloud.com/u/sets/s", "soundcloud");
        expected.put("https://example.com/playlist", null);
        expected.put("Playlists/mine.txt", null);
        expected.forEach((url, service) ->
                assertEquals(Optional.ofNullable(service), Links.detect(url), url));
    }

    @Test
    void aFilePathIsNotALinkButAnUnknownSiteIs() {
        // "no such file" would be the wrong complaint about http://...
        assertFalse(Links.isLink("Playlists/mine.txt"));
        assertFalse(Links.isLink("C:/music/list.m3u"));
        assertTrue(Links.isLink("https://example.com/playlist"));
    }

    @Test
    void aNameBecomesAFileStem() {
        assertEquals("lo-fi-beats-2024", Links.slug("Lo-Fi Beats (2024)"));
        assertEquals("my-list", Links.slug("  my   list  "));
        assertEquals("", Links.slug("***"));
        // an apostrophe is dropped, not turned into a separator
        assertEquals("todays-top-hits", Links.slug("Today\u2019s Top Hits"));
    }

    // ------------------------------------------------------------------ Spotify

    @Test
    void aPlaylistBecomesArtistTitleAndALength() {
        Linked linked = spotify(embedPage(
                track("Stop The Wedding!", "Ashe", 198840) + ","
                        + track("the cure", "Olivia Rodrigo", 297090), "Today's Top Hits"),
                SPOTIFY_URL);
        assertEquals(List.of("Ashe - Stop The Wedding! | 3:19",
                "Olivia Rodrigo - the cure | 4:57"), linked.lines());
        assertEquals("spotify", linked.source());
        assertEquals("Today's Top Hits", linked.name());
        assertEquals(List.of(), linked.warnings());
    }

    @Test
    void aNoBreakSpaceBecomesAPlainSpace() {
        // Spotify sends U+00A0 after the commas in "KAROL G, Judeline, rusowsky" (measured
        // 2026-10-15 on the live playlist). Java's \s is ASCII only, so without a Unicode
        // pattern that character reaches the search string, where it matches nothing.
        Linked linked = spotify(embedPage(
                track("Talk To You", "KAROL G, Judeline", 191000), "X"), SPOTIFY_URL);
        assertEquals(List.of("KAROL G, Judeline - Talk To You | 3:11"), linked.lines());
        assertFalse(linked.lines().get(0).contains(" "));
    }

    @Test
    void aTrackWithoutAnArtistKeepsJustTheTitle() {
        assertEquals(List.of("Untitled | 1:00"),
                spotify(embedPage(track("Untitled", "", 60000), "X"), SPOTIFY_URL).lines());
    }

    @Test
    void theHundredTrackCeilingIsReportedNotHidden() {
        // measured 2026-10-12: two different long playlists both stopped at exactly 100, so a
        // longer playlist is cut there and nothing else would tell you
        String tracks = IntStream.range(0, 100)
                .mapToObj(number -> track("Song " + number, "A", 60000))
                .collect(Collectors.joining(","));
        Linked linked = spotify(embedPage(tracks, "Big"), SPOTIFY_URL);
        assertEquals(100, linked.lines().size());
        assertTrue(said(linked, "100") && said(linked, "cut"), linked.warnings().toString());
    }

    @Test
    void aShorterPlaylistIsNotAccusedOfBeingCut() {
        String tracks = IntStream.range(0, 99)
                .mapToObj(number -> track("Song " + number, "A", 60000))
                .collect(Collectors.joining(","));
        Linked linked = spotify(embedPage(tracks, "Big"), SPOTIFY_URL);
        assertEquals(99, linked.lines().size());
        assertEquals(List.of(), linked.warnings());
    }

    @Test
    void aPlaylistThatIsNotPublicSaysSo() {
        Linked linked = spotify(embedPage("", "X"), SPOTIFY_URL);
        assertEquals(List.of(), linked.lines());
        assertTrue(said(linked, "not public"), linked.warnings().toString());
    }

    @Test
    void aChangedPageIsNamedAsSuchInsteadOfCrashing() {
        Linked linked = spotify("<html><body>nothing here</body></html>", SPOTIFY_URL);
        assertEquals(List.of(), linked.lines());
        assertTrue(said(linked, "__NEXT_DATA__"), linked.warnings().toString());
    }

    @Test
    void aLinkToATrackAsksForAPlaylistLink() {
        Linked linked = spotify(embedPage(track("x", "y", 1000), "X"),
                "https://open.spotify.com/track/abc");
        assertEquals(List.of(), linked.lines());
        assertTrue(said(linked, "playlist or album link"), linked.warnings().toString());
    }

    @Test
    void aDeadNetworkIsAWarningNotATraceback() {
        LinkReader reader = new LinkReader(url -> {
            throw new java.io.UncheckedIOException(new IOException("getaddrinfo failed"));
        }, null);
        Linked linked = reader.read(SPOTIFY_URL, null, null);
        assertEquals(List.of(), linked.lines());
        assertTrue(said(linked, "getaddrinfo"), linked.warnings().toString());
    }

    // ------------------------------------------------------------------ SoundCloud

    private static String ytdlpOutput(List<String[]> rows) {
        StringBuilder out = new StringBuilder();
        for (String[] row : rows) {
            out.append(String.join(LinkReader.FIELD_SEPARATOR, row)).append("\n");
        }
        return out.toString();
    }

    private Linked soundcloud(List<String[]> rows, int code, String tool, String cookiesFrom,
                              List<List<String>> calls) {
        ProcessRunner runner = command -> {
            if (calls != null) {
                calls.add(command);
            }
            return new ProcessRunner.Output(code, ytdlpOutput(rows), "");
        };
        return new LinkReader(null, runner).read(SC_URL,
                tool == null ? null : java.nio.file.Path.of(tool), cookiesFrom);
    }

    @Test
    void aSetBecomesLinesWithLengths() {
        Linked linked = soundcloud(List.<String[]>of(
                new String[] {"Outlier Radio", "OUTLIER Radio 003 on NTS", "bonobo", "7258.755"}),
                0, "yt-dlp.exe", null, null);
        assertEquals(List.of("bonobo - OUTLIER Radio 003 on NTS | 120:59"), linked.lines());
        assertEquals("soundcloud", linked.source());
        assertEquals("Outlier Radio", linked.name());
    }

    @Test
    void aTitleThatAlreadyNamesTheArtistIsLeftAlone() {
        // SoundCloud uploaders are not artists: prefixing twice would break the search
        Linked linked = soundcloud(List.<String[]>of(
                new String[] {"S", "Four Tet - Baby", "someuploader", "200"}),
                0, "yt-dlp.exe", null, null);
        assertEquals(List.of("Four Tet - Baby | 3:20"), linked.lines());
    }

    @Test
    void aTrackWithNoDurationKeepsTheName() {
        Linked linked = soundcloud(List.<String[]>of(new String[] {"S", "Some Mix", "dj", "NA"}),
                0, "yt-dlp.exe", null, null);
        assertEquals(List.of("dj - Some Mix"), linked.lines());
    }

    @Test
    void withoutYtDlpItSaysWhatToRun() {
        Linked linked = soundcloud(List.<String[]>of(new String[] {"S", "t", "u", "1"}),
                0, null, null, null);
        assertEquals(List.of(), linked.lines());
        assertTrue(said(linked, "setup.py") || said(linked, "setup"),
                linked.warnings().toString());
    }

    @Test
    void anEmptyAnswerPointsAtTheBrowserCookies() {
        Linked linked = soundcloud(List.of(), 0, "yt-dlp.exe", null, null);
        assertEquals(List.of(), linked.lines());
        assertTrue(said(linked, "cookies-from-browser"), linked.warnings().toString());
    }

    @Test
    void theBrowserLoginIsPassedToYtDlp() {
        List<List<String>> calls = new ArrayList<>();
        soundcloud(List.<String[]>of(new String[] {"S", "t", "u", "1"}), 0, "yt-dlp.exe", "firefox", calls);
        List<String> command = calls.get(0);
        assertTrue(command.contains("--cookies-from-browser"));
        assertEquals("firefox", command.get(command.indexOf("--cookies-from-browser") + 1));
    }

    @Test
    void aPartlyFailedSetKeepsWhatArrivedAndWarns() {
        Linked linked = soundcloud(List.<String[]>of(new String[] {"S", "t", "u", "1"}),
                1, "yt-dlp.exe", null, null);
        assertEquals(1, linked.lines().size());
        assertTrue(said(linked, "exited with 1"), linked.warnings().toString());
    }

    // ------------------------------------------------------------------ the child's output

    @Test
    void aTitleWithAccentsSurvivesThePipe() throws Exception {
        // yt-dlp is a Python program, and on Windows a Python child writes its output in the
        // console code page unless told otherwise, while we read it as UTF-8. Measured
        // 2026-10-14 on the Python tool: a SoundCloud title lost every character the code page
        // could not represent, and the damaged line went into the playlist and then into the
        // search, where Soulseek wants every word of the query to appear in the file path.
        // A real child process is run here: the bug lived between two programs, and a mock is
        // exactly what would have hidden it.
        String python = System.getProperty("python.exe", "py");
        ProcessRunner.Output output = ProcessRunner.system().run(List.of(python, "-c",
                "print('Ti\u00ebsto \u00b7 L\u00f6ffler')"));
        assertEquals(0, output.code());
        assertEquals("Ti\u00ebsto \u00b7 L\u00f6ffler", output.text().strip());
        assertFalse(output.text().contains("\ufffd"), output.text());
    }
}
