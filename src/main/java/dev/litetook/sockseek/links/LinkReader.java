package dev.litetook.sockseek.links;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.litetook.sockseek.importing.Imports;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
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
 * <p>Two services, two mechanisms, neither of which asks the person to register anything:
 *
 * <ul>
 *   <li><b>Spotify</b> - the embed page carries the whole track list as JSON: title, artist and
 *   the length in milliseconds. No login, no API key, no developer application and no Premium.
 *   Its ceiling is 100 tracks (measured 2026-10-12: two different long playlists both stopped
 *   at exactly 100), and a playlist that is not public is not there at all.
 *   <li><b>SoundCloud</b> - sockseek has no SoundCloud input type, so yt-dlp reads it. A public
 *   set needs nothing; a private one needs the browser's own cookies.
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
            "(?:open\\.spotify\\.com/(?:intl-[\\w-]+/)?(?<kind>playlist|album)/"
                    + "|spotify:(?<kind2>playlist|album):)(?<id>[A-Za-z0-9]+)");

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
                        + ". Spotify and SoundCloud playlists are supported"), "link", "");
            }
            return kind.get().equals("spotify") ? spotify(url)
                    : soundcloud(url, tool, cookiesFrom);
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
            throw new LinkError("a Spotify playlist or album link is expected, e.g. "
                    + "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M");
        }
        String kind = found.group("kind") == null ? found.group("kind2") : found.group("kind");
        JsonNode entity = entity(fetch.apply(
                String.format(SPOTIFY_EMBED, kind, found.group("id"))));
        JsonNode tracks = entity.path("trackList");
        if (!tracks.isArray() || tracks.isEmpty()) {
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

    private static JsonNode entity(String page) {
        Matcher found = NEXT_DATA.matcher(page);
        if (!found.find()) {
            throw new LinkError("the Spotify embed page has no __NEXT_DATA__ block - the page "
                    + "changed, so this needs a look");
        }
        JsonNode node;
        try {
            node = new ObjectMapper().readTree(found.group(1));
        } catch (IOException notJson) {
            throw new LinkError("the Spotify embed page's JSON did not parse - the page changed");
        }
        for (String key : ENTITY_PATH) {
            node = node.path(key);
        }
        if (!node.isObject()) {
            throw new LinkError("the Spotify page has no " + String.join(".", ENTITY_PATH)
                    + " - the page changed, so this needs a look");
        }
        return node;
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
            return response.body();
        } catch (IOException cannotFetch) {
            throw new UncheckedIOException(cannotFetch);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new LinkError("the page fetch was interrupted");
        }
    }
}
