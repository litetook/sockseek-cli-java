package dev.litetook.sockseek.links;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.litetook.sockseek.importing.Imports;
import dev.litetook.sockseek.process.ProcessRunner;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turning a playlist link into playlist lines.
 *
 * <p>Three services, three mechanisms, none of which asks the person to register anything:
 *
 * <ul>
 *   <li><b>Spotify</b> - the embed page carries the whole track list as JSON: title, artist and
 *   the length in milliseconds. No login, no API key, no developer application and no Premium.
 *   Its ceiling is 100 tracks (measured 2026-10-12: two different long playlists both stopped
 *   at exactly 100), and a playlist that is not public is not there at all.
 *   <li><b>SoundCloud</b> - sockseek has no SoundCloud input type, so yt-dlp reads it. A public
 *   set needs nothing; a private one needs the browser's own cookies.
 *   <li><b>Beatport</b> - the page itself carries its tracks as JSON, the same Next.js shape
 *   Spotify uses, with no login and no key. Measured 2026-10-19 on three page kinds -
 *   {@code /top-100}, {@code /genre/<name>/<id>/top-100} and {@code /chart/<name>/<id>} - which
 *   differ only in which query of the page holds the tracks, so the tracks are looked for by
 *   their own shape rather than at a fixed path. A page under {@code /library/} is behind a
 *   login and cannot be read at all.
 * </ul>
 *
 * <p>A link only ever becomes a playlist file. The downloading stays with the engine, so a link
 * goes through the same "this is what is missing, confirm it" workflow as a hand-written list.
 */
public final class LinkReader {

    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Safari/537.36";
    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(25);
    private static final String SPOTIFY_EMBED = "https://open.spotify.com/embed/%s/%s";

    private static final Pattern NEXT_DATA = Pattern.compile(
            "<script id=\"__NEXT_DATA__\"[^>]*>(.*?)</script>", Pattern.DOTALL);
    private static final List<String> ENTITY_PATH =
            List.of("props", "pageProps", "state", "data", "entity");
    private static final Pattern SPOTIFY_REF = Pattern.compile(
            "(?:open\\.spotify\\.com/(?:intl-[\\w-]+/)?(?<kind>playlist|album|track)/"
                    + "|spotify:(?<kind2>playlist|album|track):)(?<id>[A-Za-z0-9]+)");

    /** A separator no track title carries, so yt-dlp's one line per track splits safely. */
    public static final String FIELD_SEPARATOR = "\u001f";
    private static final String YTDLP_FIELDS = String.join(FIELD_SEPARATOR,
            "%(playlist_title)s", "%(title)s", "%(uploader)s", "%(duration)s");
    private static final Set<String> UNKNOWN = Set.of("", "na", "none", "null");

    private final UnaryOperator<String> fetch;
    private final ProcessRunner runner;

    public LinkReader() {
        this(null, null);
    }

    /** Both are injected so the tests never reach the network or start a program. */
    public LinkReader(UnaryOperator<String> fetch, ProcessRunner runner) {
        this.fetch = fetch == null ? LinkReader::get : fetch;
        this.runner = runner == null ? ProcessRunner.system() : runner;
    }

    /**
     * The one entry point. Never throws for a link we simply could not read: the reason comes
     * back as a warning, so the caller reports it like any other import problem.
     */
    public Linked read(String url, Path tool, String cookiesFrom) {
        Optional<String> kind = Links.detect(url);
        try {
            if (kind.isEmpty()) {
                return new Linked(List.of(), List.of("not a link this tool can read: " + url
                        + ". Spotify, SoundCloud and Beatport playlists are supported"),
                        "link", "");
            }
            return switch (kind.get()) {
                case "spotify" -> spotify(url);
                case "beatport" -> beatport(url);
                default -> soundcloud(url, tool, cookiesFrom);
            };
        } catch (LinkError problem) {
            return new Linked(List.of(), List.of(problem.getMessage()), kind.orElse("link"), "");
        } catch (RuntimeException problem) {   // a dead network, a 404, a timeout
            return new Linked(List.of(), List.of("could not open the " + kind.orElse("link")
                    + " link: " + message(problem)), kind.orElse("link"), "");
        }
    }

    private static String message(Throwable problem) {
        Throwable cause = problem.getCause() == null ? problem : problem.getCause();
        return cause.getMessage() == null ? cause.toString() : cause.getMessage();
    }

    // ------------------------------------------------------------------ Spotify

    public Linked spotify(String url) {
        Matcher found = SPOTIFY_REF.matcher(url == null ? "" : url);
        if (!found.find()) {
            throw new LinkError("a Spotify playlist, album or track link is expected, e.g. "
                    + "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M");
        }
        String kind = found.group("kind") == null ? found.group("kind2") : found.group("kind");
        JsonNode entity = entity(fetch.apply(
                String.format(SPOTIFY_EMBED, kind, found.group("id"))));
        JsonNode tracks = entity.path("trackList");
        if (!tracks.isArray() || tracks.isEmpty()) {
            // a single track embeds itself rather than a list, so the entity IS the track
            Optional<String> alone = oneTrack(entity);
            if (alone.isPresent()) {
                return new Linked(List.of(alone.get()), List.of(), "spotify",
                        entity.path("name").asText(""));
            }
            throw new LinkError("the Spotify page carries no track list. A playlist that is not "
                    + "public cannot be read this way");
        }
        List<String> lines = new ArrayList<>();
        for (JsonNode track : tracks) {
            String title = track.path("title").asText("").strip();
            if (title.isEmpty()) {
                continue;
            }
            String artist = track.path("subtitle").asText("").strip();
            OptionalInt milliseconds = seconds(track.path("duration").asText(""));
            OptionalInt length = milliseconds.isPresent()
                    ? OptionalInt.of((int) Math.round(milliseconds.getAsInt() / 1000.0))
                    : OptionalInt.empty();
            lines.add(Imports.trackLine(artist.isEmpty() ? title : artist + " - " + title,
                    length));
        }
        List<String> warnings = tracks.size() >= Links.EMBED_MAX
                ? List.of("Spotify's page gives at most " + Links.EMBED_MAX + " tracks and "
                        + "returned exactly that, so a longer playlist is cut here. Check the file")
                : List.<String>of();
        return new Linked(lines, warnings, "spotify", entity.path("name").asText(""));
    }

    /**
     * One track out of a track embed: its own name, its first artist, its length.
     *
     * <p>Empty when the entity is not a track at all, which is how a private playlist is told
     * apart from a single track.
     */
    private static Optional<String> oneTrack(JsonNode entity) {
        String title = entity.path("name").asText("").strip();
        // the page has to SAY it is a track: a private playlist also carries a name and no
        // track list, and reading that as a one-track playlist would hide the real problem
        if (title.isEmpty() || !entity.path("type").asText("").strip().equals("track")) {
            return Optional.empty();
        }
        JsonNode artists = entity.path("artists");
        String artist = artists.isArray() && !artists.isEmpty()
                ? artists.get(0).path("name").asText("").strip() : "";
        OptionalInt milliseconds = seconds(entity.path("duration").asText(""));
        OptionalInt length = milliseconds.isPresent()
                ? OptionalInt.of((int) Math.round(milliseconds.getAsInt() / 1000.0))
                : OptionalInt.empty();
        return Optional.of(Imports.trackLine(
                artist.isEmpty() ? title : artist + " - " + title, length));
    }

    /**
     * The JSON a Next.js page carries inside {@code __NEXT_DATA__}.
     *
     * <p>Shared by Spotify and Beatport: both are Next.js pages, and both fail the same two
     * ways - the block is gone, or it is no longer JSON. Saying which service it was keeps the
     * message useful when the page, and not this tool, is what changed.
     */
    private static JsonNode nextData(String page, String service) {
        Matcher found = NEXT_DATA.matcher(page == null ? "" : page);
        if (!found.find()) {
            throw new LinkError("the " + service + " page has no __NEXT_DATA__ block - the page "
                    + "changed, so this needs a look");
        }
        try {
            return new ObjectMapper().readTree(found.group(1));
        } catch (IOException notJson) {
            throw new LinkError("the " + service + " page's JSON did not parse - the page changed");
        }
    }

    private static JsonNode entity(String page) {
        JsonNode node = nextData(page, "Spotify embed");
        for (String key : ENTITY_PATH) {
            node = node.path(key);
        }
        if (!node.isObject()) {
            throw new LinkError("the Spotify page has no " + String.join(".", ENTITY_PATH)
                    + " - the page changed, so this needs a look");
        }
        return node;
    }

    // ------------------------------------------------------------------ Beatport

    /**
     * A Beatport chart, genre top 100 or the front top 100, as playlist lines.
     *
     * <p>The tracks are found by their own shape - an object carrying both {@code mix_name} and
     * {@code artists} - rather than at a fixed path. Measured 2026-10-19: the three page kinds
     * put the list under a different {@code queries[N]} each, so a fixed path would read one of
     * them and silently find nothing on the other two.
     */
    public Linked beatport(String url) {
        String where = url == null ? "" : url.toLowerCase(Locale.ROOT);
        // Only the pages whose shape was measured. A track page carries 202 track-shaped
        // objects - the charts it appears in and what Beatport recommends next - so reading
        // one "like a list" would hand back two hundred tracks nobody asked for
        // (measured 2026-10-19 on /track/think-of-you/30860950).
        if (!where.contains("/top-100") && !where.contains("/chart/")) {
            throw new LinkError("this Beatport page is not one this tool reads. A chart "
                    + "(/chart/...), a genre top 100 (/genre/.../top-100) or "
                    + "beatport.com/top-100 can be read; a track, release or library page "
                    + "cannot");
        }
        JsonNode root = nextData(fetch.apply(url), "Beatport");
        List<JsonNode> tracks = new ArrayList<>();
        collect(root, tracks, new HashSet<>());
        if (tracks.isEmpty()) {
            throw new LinkError("the Beatport page carries no track list. A page under "
                    + "/library/ is behind a login; a chart, a genre top 100 or "
                    + "beatport.com/top-100 can be read");
        }
        List<String> lines = new ArrayList<>();
        for (JsonNode track : tracks) {
            String title = track.path("name").asText("").strip();
            if (title.isEmpty()) {
                continue;
            }
            String mix = track.path("mix_name").asText("").strip();
            // "Think of You" + "Extended" -> "Think of You (Extended)", because that is how
            // the file is named on the network. A mix already inside the title is not repeated
            if (!mix.isEmpty()
                    && !title.toLowerCase(Locale.ROOT).contains(mix.toLowerCase(Locale.ROOT))) {
                title = title + " (" + mix + ")";
            }
            // the FIRST artist only: a Beatport credit runs to three or four names on a
            // collaboration, and that whole string as a search finds nothing
            JsonNode artists = track.path("artists");
            String artist = artists.isArray() && !artists.isEmpty()
                    ? artists.get(0).path("name").asText("").strip() : "";
            lines.add(Imports.trackLine(artist.isEmpty() ? title : artist + " - " + title,
                    milliseconds(track.path("length_ms"))));
        }
        return new Linked(lines, beatportWarnings(root), "beatport", beatportName(root, url));
    }

    /** Every object below this node that looks like a Beatport track, in page order, once each. */
    private static void collect(JsonNode node, List<JsonNode> found, Set<String> seen) {
        if (node.isArray()) {
            node.forEach(child -> collect(child, found, seen));
            return;
        }
        if (!node.isObject()) {
            return;
        }
        if (node.has("mix_name") && node.path("artists").isArray()) {
            // the same track can sit in more than one query of the page - the chart's own
            // preview and the chart's track list both carry it
            if (seen.add(node.path("id").asText(node.path("name").asText("")))) {
                found.add(node);
            }
            return;
        }
        node.forEach(child -> collect(child, found, seen));
    }

    /**
     * What to call the file: the chart's or the genre's own name, and "Beatport Top 100" for the
     * front page, which carries no name of its own.
     */
    private static String beatportName(JsonNode root, String url) {
        String path = url == null ? "" : url.toLowerCase(Locale.ROOT);
        String name = "";
        for (JsonNode query : root.path("props").path("pageProps").path("dehydratedState")
                .path("queries")) {
            JsonNode data = query.path("state").path("data");
            // the page of tracks has "results" and no name; the chart or genre object has a name
            if (data.isObject() && !data.has("results") && !data.path("name").asText("").isBlank()) {
                name = data.path("name").asText("").strip();
                break;
            }
        }
        if (name.isEmpty()) {
            return path.contains("/top-100") ? "Beatport Top 100" : "Beatport";
        }
        return path.endsWith("/top-100") ? name + " Top 100" : name;
    }

    /** Beatport hands out one page of 100; {@code next} says a longer list was cut here. */
    private static List<String> beatportWarnings(JsonNode root) {
        for (JsonNode query : root.path("props").path("pageProps").path("dehydratedState")
                .path("queries")) {
            JsonNode data = query.path("state").path("data");
            if (data.isObject() && data.path("results").isArray()
                    && !data.path("next").isNull() && data.path("next").isTextual()) {
                return List.of("Beatport gives " + data.path("results").size() + " tracks a page "
                        + "and this list has " + data.path("count").asInt() + ", so the rest are "
                        + "not here. Check the file");
            }
        }
        return List.of();
    }

    /** Beatport reports a length in milliseconds as a number, not as text. */
    private static OptionalInt milliseconds(JsonNode node) {
        if (!node.isNumber() || node.asLong() <= 0) {
            return OptionalInt.empty();
        }
        return OptionalInt.of((int) Math.round(node.asLong() / 1000.0));
    }

    // ------------------------------------------------------------------ SoundCloud

    public Linked soundcloud(String url, Path tool, String cookiesFrom) {
        if (tool == null) {
            throw new LinkError("reading a SoundCloud link needs yt-dlp: run `py setup.py --all`");
        }
        List<String> command = new ArrayList<>(List.of(tool.toString(), "--no-warnings",
                "--ignore-errors", "--skip-download", "--print", YTDLP_FIELDS));
        if (cookiesFrom != null && !cookiesFrom.isBlank()) {
            command.add("--cookies-from-browser");
            command.add(cookiesFrom);
        }
        command.add(url);
        ProcessRunner.Output output = runner.run(command);
        List<String> lines = new ArrayList<>();
        String name = "";
        for (String row : output.text().lines().toList()) {
            if (!row.contains(FIELD_SEPARATOR)) {
                continue;
            }
            String[] fields = row.split(FIELD_SEPARATOR, -1);
            String playlist = field(fields, 0);
            String title = field(fields, 1);
            String uploader = field(fields, 2);
            String duration = field(fields, 3);
            if (name.isEmpty() && !UNKNOWN.contains(playlist.toLowerCase(Locale.ROOT))) {
                name = playlist;
            }
            if (title.isEmpty() || UNKNOWN.contains(title.toLowerCase(Locale.ROOT))) {
                continue;
            }
            // a SoundCloud title is often already "Artist - Title"; when it is not, the
            // uploader is the best guess at the artist, and may well be wrong
            String track = title.contains(" - ") ? title
                    : UNKNOWN.contains(uploader.toLowerCase(Locale.ROOT)) ? title
                            : uploader + " - " + title;
            lines.add(Imports.trackLine(track, seconds(duration)));
        }
        if (lines.isEmpty()) {
            throw new LinkError("yt-dlp returned no tracks for this link"
                    + (output.code() == 0 ? "" : " (it exited with " + output.code() + ")")
                    + (cookiesFrom == null || cookiesFrom.isBlank()
                            ? ". A private set needs --cookies-from-browser <browser>" : ""));
        }
        List<String> warnings = output.code() == 0 ? List.of()
                : List.of("yt-dlp exited with " + output.code()
                        + ": some tracks of the set may be missing");
        return new Linked(lines, warnings, "soundcloud", name);
    }

    private static String field(String[] fields, int index) {
        return index < fields.length ? fields[index].strip() : "";
    }

    /** yt-dlp prints {@code 249.03} or {@code NA}; Spotify gives milliseconds as a number. */
    private static OptionalInt seconds(String text) {
        String value = text == null ? "" : text.strip();
        if (UNKNOWN.contains(value.toLowerCase(Locale.ROOT))) {
            return OptionalInt.empty();
        }
        try {
            double number = Double.parseDouble(value);
            return number > 0 && Double.isFinite(number)
                    ? OptionalInt.of((int) Math.round(number)) : OptionalInt.empty();
        } catch (NumberFormatException notANumber) {
            return OptionalInt.empty();
        }
    }

    private static String get(String url) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .timeout(FETCH_TIMEOUT)
                .build();
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(FETCH_TIMEOUT).build()) {
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                // Without this the error page came back as a body and the parser then blamed
                // the page for having changed, sending the owner to investigate a change that
                // never happened. A deleted or region-blocked playlist is a link problem and
                // should say so (found by an independent review, 2026-10-16).
                throw new LinkError("HTTP " + response.statusCode() + " for " + url
                        + " - the playlist may be private, deleted or blocked where you are");
            }
            return response.body();
        } catch (IOException cannotFetch) {
            throw new UncheckedIOException(cannotFetch);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new LinkError("the page fetch was interrupted");
        }
    }
}
