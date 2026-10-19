package dev.litetook.sockseek.links;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
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
        expected.put("https://www.beatport.com/top-100", "beatport");
        expected.put("https://www.beatport.com/chart/some-chart/906162", "beatport");
        expected.put("https://beatport.com/genre/techno/6/top-100", "beatport");
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
    void aLinkToATrackIsReadAsThatOneTrack() {
        // refused until 2026-10-18, when option 4 - one track into unsorted - gave it a
        // meaning. A track page that still carries a trackList is read from the list
        Linked linked = spotify(embedPage(track("x", "y", 1000), "X"),
                "https://open.spotify.com/track/abc");
        assertEquals(List.of("y - x | 0:01"), linked.lines());
        assertEquals(List.of(), linked.warnings());
    }

    @Test
    void aLinkToSomethingElseEntirelyStillAsksForOneWeCanRead() {
        Linked linked = new LinkReader(any -> "", null)
                .read("https://example.com/song", null, null);
        assertEquals(List.of(), linked.lines());
        assertTrue(said(linked, "not a link this tool can read"), linked.warnings().toString());
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
        // yt-dlp is written in Python, and on Windows such a child writes its output in the
        // console code page unless PYTHONIOENCODING says otherwise, while we read it as
        // UTF-8. Measured 2026-10-14: a SoundCloud title lost every character the code page
        // could not represent, the damaged line went into the playlist and then into the
        // search, and Soulseek wants every word of the query to appear in the file path.
        //
        // So an interpreter is started here on purpose, and this is the ONLY place in the
        // project that needs one: the thing under test is an environment variable reaching
        // a child, the bug lived between two programs, and a mock is exactly what would
        // have hidden it. Where there is no interpreter there is nothing to prove and the
        // test stands aside - which is what happens in CI.
        String interpreter = interpreter();
        assumeTrue(interpreter != null, "no Python interpreter, and yt-dlp is written in one");
        ProcessRunner.Output output = ProcessRunner.system().run(List.of(interpreter, "-c",
                "print('Ti\u00ebsto \u00b7 L\u00f6ffler')"));
        assertEquals(0, output.code());
        assertEquals("Ti\u00ebsto \u00b7 L\u00f6ffler", output.text().strip());
        assertFalse(output.text().contains("\ufffd"), output.text());
    }


    /** Whichever of the usual names answers, or nothing. */

    private static String interpreter() {

        for (String name : List.of(System.getProperty("python.exe", "python"), "py",

                "python3")) {

            try {

                if (ProcessRunner.system().run(List.of(name, "--version")).code() == 0) {

                    return name;

                }

            } catch (RuntimeException notThisOne) {

                continue;

            }

        }

        return null;

    }


    /**
     * A single track embeds itself instead of a list - measured against the live page on
     * 2026-10-18 for spotify:track:1pKYYY0dkg23sQQXi0Q5zN: the entity carries name, artists
     * and duration in milliseconds, and no trackList at all.
     */
    private static String trackPage(String name, String artist, long durationMs) {
        return "<!DOCTYPE html><html><body>"
                + "<script id=\"__NEXT_DATA__\" type=\"application/json\">"
                + "{\"props\":{\"pageProps\":{\"state\":{\"data\":{\"entity\":{"
                + "\"name\":\"" + name + "\",\"type\":\"track\",\"duration\":" + durationMs
                + ",\"artists\":[{\"name\":\"" + artist + "\"}]}}}}}}"
                + "</script></body></html>";
    }

    @Test
    void aSingleTrackLinkIsOneLine() {
        Linked read = spotify(trackPage("Around the World", "Daft Punk", 429533),
                "https://open.spotify.com/track/1pKYYY0dkg23sQQXi0Q5zN");
        // 429533 ms rounds to 7:10; Spotify shows 7:09 because it cuts the fraction, and
        // the length is a tolerance window here, not a label
        assertEquals(List.of("Daft Punk - Around the World | 7:10"), read.lines());
        assertEquals(List.of(), read.warnings());
    }

    @Test
    void theSpotifyUriFormOfATrackWorksToo() {
        Linked read = spotify(trackPage("Porcelain", "Moby", 241000),
                "spotify:track:1pKYYY0dkg23sQQXi0Q5zN");
        assertEquals(List.of("Moby - Porcelain | 4:01"), read.lines());
    }

    @Test
    void aTrackWithNoLengthIsStillATrack() {
        // then it is searched without a length filter rather than not at all
        Linked read = spotify(trackPage("Untitled", "Nobody", 0),
                "https://open.spotify.com/track/abc123");
        assertEquals(List.of("Nobody - Untitled"), read.lines());
    }

    @Test
    void aPrivatePlaylistStillSaysWhatIsWrong() {
        // the entity of an unreadable playlist has no name either, so it must not be read as
        // a single track with an empty title
        String page = "<!DOCTYPE html><html><body>"
                + "<script id=\"__NEXT_DATA__\" type=\"application/json\">"
                + "{\"props\":{\"pageProps\":{\"state\":{\"data\":{\"entity\":{}}}}}}"
                + "</script></body></html>";
        Linked read = spotify(page, SPOTIFY_URL);
        assertEquals(List.of(), read.lines());
        assertTrue(said(read, "no track list"), read.warnings().toString());
    }

    // ------------------------------------------------------------------ Beatport
    //
    // The three fixtures are the real pages of 2026-10-19 with everything but the track list
    // and the chart's name cut out: same paths, same field names, same values. The three kinds
    // put the tracks under a different queries[N] each, which is the whole reason the reader
    // looks for them by shape.

    private static String page(String name) {
        try (java.io.InputStream stream =
                     LinkReaderTest.class.getResourceAsStream("/links/" + name)) {
            return new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException | NullPointerException missing) {
            throw new IllegalStateException("fixture not on the test classpath: " + name, missing);
        }
    }

    private static Linked beatport(String fixture, String url) {
        return new LinkReader(any -> page(fixture), null).read(url, null, null);
    }

    @Test
    void aBeatportChartBecomesPlaylistLines() {
        Linked read = beatport("beatport-chart.html",
                "https://www.beatport.com/chart/lasting-memories-refraction-chart/906162");
        assertEquals(List.of(
                "Luis Damora - Lasting Memories (Extended Mix) | 7:54",
                "Doug Crawford - Refraction (Luis Damora Remix) | 8:01",
                "Barberio - Isabella (Original Mix) | 7:09"), read.lines());
        assertEquals("beatport", read.source());
        assertEquals("LASTING MEMORIES REFRACTION CHART", read.name());
        assertEquals(List.of(), read.warnings());
    }

    @Test
    void aGenreTopHundredIsNamedAfterItsGenre() {
        Linked read = beatport("beatport-genre-top-100.html",
                "https://www.beatport.com/genre/techno-peak-time-driving/6/top-100");
        assertEquals("Techno (Peak Time / Driving) Top 100", read.name());
        assertEquals("Faithless - We Come 1 (Adam Beyer Remix) | 6:00", read.lines().get(0));
    }

    @Test
    void theFrontTopHundredCarriesNoNameOfItsOwn() {
        Linked read = beatport("beatport-top-100.html", "https://www.beatport.com/top-100");
        assertEquals("Beatport Top 100", read.name());
        // the mix goes in brackets, and only the first artist is searched for
        assertEquals("Prospa - Think of You (Extended) | 5:44", read.lines().get(0));
    }

    @Test
    void aListLongerThanOnePageSaysSo() {
        // the same shape with "next" filled in, which is how Beatport reports a second page
        String cut = page("beatport-chart.html")
                .replace("\"next\": null", "\"next\": \"https://api.beatport.com/v4/next\"")
                .replace("\"count\": 10", "\"count\": 250");
        Linked read = new LinkReader(any -> cut, null)
                .read("https://www.beatport.com/chart/x/1", null, null);
        assertEquals(3, read.lines().size());
        assertTrue(said(read, "so the rest are not here"), read.warnings().toString());
    }

    @Test
    void onlyTheChartAndTopPagesOfBeatportAreRead() {
        // a track page carries the charts it appears in and what Beatport suggests next -
        // 202 track-shaped objects, measured 2026-10-19 - so it is refused before any fetch
        for (String url : List.of("https://www.beatport.com/track/think-of-you/30860950",
                "https://www.beatport.com/library/playlists/1",
                "https://www.beatport.com/release/some-ep/5123")) {
            Linked read = new LinkReader(any -> {
                throw new IllegalStateException("must not fetch " + url);
            }, null).read(url, null, null);
            assertEquals(List.of(), read.lines(), url);
            assertTrue(said(read, "/chart/"), read.warnings().toString());
        }
    }

    @Test
    void aBeatportPageWithNoTracksSaysSo() {
        String empty = "<!DOCTYPE html><html><body>"
                + "<script id=\"__NEXT_DATA__\" type=\"application/json\">"
                + "{\"props\":{\"pageProps\":{\"dehydratedState\":{\"queries\":[]}}}}"
                + "</script></body></html>";
        Linked read = new LinkReader(any -> empty, null)
                .read("https://www.beatport.com/chart/gone/1", null, null);
        assertEquals(List.of(), read.lines());
        assertTrue(said(read, "no track list"), read.warnings().toString());
    }
}

