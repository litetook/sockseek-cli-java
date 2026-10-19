package dev.litetook.sockseek.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.litetook.sockseek.config.Settings;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The page, over real HTTP, against a real folder.
 *
 * <p>What is checked is what a browser would do and what another site would try: the state is
 * answered for the folder as it is, a request without the key is refused, a request with a
 * foreign Host header is refused, and the two writes - a playlist and the settings - keep a
 * backup and refuse a file that would not load.
 *
 * <p>Nothing here downloads anything: no run is started, so sockseek is never reached.
 */
class WebTest {

    @TempDir
    Path appDir;

    private Web.Running server;
    private String key;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeEach
    void up() throws IOException {
        Settings.ensureFiles(appDir, line -> { });
        Files.createDirectories(appDir.resolve("Playlists"));
        Files.writeString(appDir.resolve("Playlists/techno.txt"),
                "Artist One - First | 4:00\nArtist Two - Second | 5:30\n",
                StandardCharsets.UTF_8);
        Optional<Web.Running> up = Web.start(appDir, 0, line -> { }, false);
        assertTrue(up.isPresent(), "the server did not start");
        server = up.get();
        // the address it prints carries the key, which is the only way in
        key = server.url().substring(server.url().indexOf("?t=") + 3);
    }

    @AfterEach
    void down() {
        if (server != null) {
            server.close();
        }
    }

    private HttpRequest.Builder to(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .header("X-Sockseek-Key", key)
                .timeout(Duration.ofSeconds(10));
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException broke) {
            throw new IllegalStateException(broke);
        }
    }

    // ------------------------------------------------------------------ reading

    @Test
    void theStateDescribesTheFolderAsItIs() {
        HttpResponse<String> answer = send(to("/api/state").GET().build());
        assertEquals(200, answer.statusCode());
        String body = answer.body();
        assertTrue(body.contains("\"name\":\"techno\""), body);
        assertTrue(body.contains("\"tracks\":2"), body);
        // a fresh folder has no login, and the page says so rather than pretending
        assertTrue(body.contains("\"loginSet\":false"), body);
        assertTrue(body.contains("\"running\":false"), body);
    }

    @Test
    void thePageItselfIsServedFromTheJar() {
        HttpResponse<String> answer = send(to("/").GET().build());
        assertEquals(200, answer.statusCode());
        assertTrue(answer.body().contains("sockseek-cli"), "not the page");
        assertEquals("text/html; charset=utf-8",
                answer.headers().firstValue("Content-Type").orElse(""));
    }

    // ------------------------------------------------------------------ the two guards

    @Test
    void withoutTheKeyNoRouteThatDoesAnythingAnswers() {
        for (String path : List.of("/api/state", "/api/config", "/api/stream")) {
            HttpResponse<String> answer = send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + server.port() + path)).GET().build());
            assertEquals(403, answer.statusCode(), path);
        }
        HttpResponse<String> wrong = send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + server.port() + "/api/state?t=nope"))
                .GET().build());
        assertEquals(403, wrong.statusCode());
    }

    @Test
    void thePagesOwnThreeFilesNeedNoKey() {
        // the browser sends no key of ours for the stylesheet and the script named in the
        // HTML, and a page served without them is a bare page (measured 2026-10-19)
        for (String file : List.of("/", "/app.css", "/app.js")) {
            HttpResponse<String> answer = send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + server.port() + file)).GET().build());
            assertEquals(200, answer.statusCode(), file);
        }
    }

    @Test
    void aForeignHostHeaderIsRefusedEvenWithTheKey() throws IOException {
        // What DNS rebinding looks like: the request really does arrive at 127.0.0.1, but the
        // browser believes it is talking to someone else's name and sends that name along.
        // Written on a socket because HttpClient refuses to let anyone set Host at all
        String answer = raw(String.join("\r\n",
                "GET /api/state?t=" + key + " HTTP/1.1",
                "Host: evil.example.com",
                "Connection: close",
                "", ""));
        assertTrue(answer.startsWith("HTTP/1.1 403"), answer);
        assertTrue(answer.contains("this machine"), answer);
    }

    /** One request, written by hand, for the headers a client library will not send. */
    private String raw(String request) throws IOException {
        try (java.net.Socket socket = new java.net.Socket("127.0.0.1", server.port())) {
            socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // ------------------------------------------------------------------ writing a playlist

    @Test
    void savingAPlaylistKeepsACopyOfTheOldOne() throws IOException {
        HttpResponse<String> answer = send(to("/api/playlists/techno")
                .PUT(HttpRequest.BodyPublishers.ofString(
                        "{\"text\":\"Only One - Track | 3:00\"}", StandardCharsets.UTF_8))
                .build());
        assertEquals(200, answer.statusCode(), answer.body());
        assertEquals("Only One - Track | 3:00\n",
                Files.readString(appDir.resolve("Playlists/techno.txt")));
        try (Stream<Path> files = Files.list(appDir.resolve("Playlists"))) {
            assertTrue(files.anyMatch(file -> file.getFileName().toString()
                    .startsWith("techno.txt.bak-")), "no backup was kept");
        }
    }

    @Test
    void aNameThatClimbsOutOfTheFolderIsRefused() throws IOException {
        HttpResponse<String> answer = send(to("/api/playlists/..%2F..%2Fescaped")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"text\":\"x\"}"))
                .build());
        assertEquals(400, answer.statusCode(), answer.body());
        assertFalse(Files.exists(appDir.resolve("../escaped.txt")), "it wrote outside");
    }

    // ------------------------------------------------------------------ writing the settings

    @Test
    void settingsThatWouldNotLoadAreRefusedAndTheFileIsLeftAlone() throws IOException {
        String was = Files.readString(appDir.resolve(Settings.CONFIG_NAME));
        String broken = "playlist:\n  attempt_timeout_min: 60mim\n";
        HttpResponse<String> answer = send(to("/api/config")
                .PUT(HttpRequest.BodyPublishers.ofString(
                        "{\"text\":" + quote(broken) + "}", StandardCharsets.UTF_8))
                .build());
        assertEquals(400, answer.statusCode(), answer.body());
        assertTrue(answer.body().contains("attempt_timeout_min"), answer.body());
        // the complaint names the owner's file, not the temporary one it was checked in
        assertTrue(answer.body().contains(Settings.CONFIG_NAME), answer.body());
        assertEquals(was, Files.readString(appDir.resolve(Settings.CONFIG_NAME)));
    }

    @Test
    void settingsTheOwnerInsistsOnAreWrittenWithABackup() throws IOException {
        String good = "playlist:\n  attempt_timeout_min: 30min\n";
        HttpResponse<String> answer = send(to("/api/config")
                .PUT(HttpRequest.BodyPublishers.ofString(
                        "{\"text\":" + quote(good) + "}", StandardCharsets.UTF_8))
                .build());
        assertEquals(200, answer.statusCode(), answer.body());
        assertEquals(good, Files.readString(appDir.resolve(Settings.CONFIG_NAME)));
        try (Stream<Path> files = Files.list(appDir)) {
            assertTrue(files.anyMatch(file -> file.getFileName().toString()
                    .startsWith(Settings.CONFIG_NAME + ".bak-")), "no backup was kept");
        }
    }

    // ------------------------------------------------------------------ the login

    @Test
    void theLoginIsWrittenAndNeverGivenBack() throws IOException {
        HttpResponse<String> saved = send(to("/api/login")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"username\":\"someone\",\"password\":\"a-secret\"}"))
                .build());
        assertEquals(200, saved.statusCode(), saved.body());
        assertTrue(Files.readString(appDir.resolve(Settings.LOCAL_CONFIG_NAME))
                .contains("someone"));
        HttpResponse<String> state = send(to("/api/state").GET().build());
        assertTrue(state.body().contains("\"loginSet\":true"), state.body());
        assertFalse(state.body().contains("a-secret"), "the page was told the password");
    }

    /** JSON string quoting, for the two bodies that carry a whole file. */
    private static String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n") + "\"";
    }
}
