package dev.litetook.sockseek.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import dev.litetook.sockseek.App;
import dev.litetook.sockseek.cli.Cli;
import dev.litetook.sockseek.config.Config;
import dev.litetook.sockseek.config.Settings;
import dev.litetook.sockseek.links.Links;
import dev.litetook.sockseek.playlist.Lint;
import dev.litetook.sockseek.process.Trace;
import dev.litetook.sockseek.process.Runner;
import dev.litetook.sockseek.process.Options;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * What the page can ask for.
 *
 * <p>Every answer is JSON, every route is one short method, and nothing here does any of the
 * work itself: the run is {@link App#run}, the import is the same {@code --import} the console
 * takes, the login goes through {@link Settings} and a playlist is a text file. A second
 * implementation of any of that would be a second thing to keep right.
 *
 * <p>Writes are the part worth care. A playlist and the settings file are hand-edited, so each
 * write takes a backup beside the file first, and the settings are parsed before they are
 * written - a file that would not load is refused with the reason, not saved and discovered at
 * the next run.
 */
final class Api {

    /** The stamp on a backup, as the owner's own files already carry it. */
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");

    /** A playlist name is a file name, so these are the characters a file name may have here. */
    private static final java.util.regex.Pattern SAFE_NAME =
            java.util.regex.Pattern.compile("[^\\p{IsAlphabetic}\\p{IsDigit} ()\\[\\]&#._'-]+");

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path appDir;
    private final Jobs jobs;
    private final Stream stream;

    Api(Path appDir, Jobs jobs, Stream stream) {
        this.appDir = appDir;
        this.jobs = jobs;
        this.stream = stream;
    }

    // ------------------------------------------------------------------ the routes

    void handle(HttpExchange call) throws IOException {
        String path = call.getRequestURI().getPath();
        String method = call.getRequestMethod();
        try {
            switch (path) {
                case "/api/state" -> text(call, 200, state(appDir, jobs), "application/json");
                case "/api/stream" -> watch(call);
                case "/api/run" -> startRun(call, method);
                case "/api/stop" -> stopRun(call, method);
                case "/api/config" -> config(call, method);
                case "/api/settings" -> settings(call, method);
                case "/api/login" -> login(call, method);
                case "/api/login/check" -> loginCheck(call, method);
                case "/api/link" -> link(call, method);
                default -> {
                    if (path.startsWith("/api/playlists/")) {
                        playlist(call, method,
                                path.substring("/api/playlists/".length()));
                    } else {
                        fail(call, 404, "no such route: " + path);
                    }
                }
            }
        } catch (Refused refused) {
            fail(call, refused.code, refused.getMessage());
        } catch (RuntimeException broke) {
            fail(call, 500, broke.getClass().getSimpleName() + ": " + broke.getMessage());
        }
    }

    /** Everything the page needs to draw itself, and to know whether a run is going. */
    static String state(Path appDir, Jobs jobs) {
        List<String> warnings = new ArrayList<>();
        Config config = Settings.loadConfig(appDir.resolve(Settings.CONFIG_NAME), warnings::add);
        java.util.Map<String, Path> folders = Settings.configPaths(config, appDir);
        ObjectNode out = JSON.createObjectNode();
        out.put("version", Cli.VERSION);
        out.put("folder", appDir.toString());
        out.put("loginSet", !Settings.missingLogin(appDir));
        out.put("running", jobs.running());
        out.put("dryRun", jobs.dry());
        out.put("stopping", jobs.stopping());
        out.put("passes", App.passes(config));
        out.put("perWindow", App.searchesPerWindow(config));
        out.put("windowSeconds", App.windowSeconds(config));
        out.put("configPath", appDir.resolve(Settings.CONFIG_NAME).toString());
        ArrayNode rows = out.putArray("playlists");
        int tracks = 0;
        for (App.Info info : App.scan(folders.get("playlists"), folders.get("music"))) {
            ObjectNode row = rows.addObject();
            row.put("name", info.name());
            row.put("tracks", info.total());
            row.put("here", info.have());
            row.put("missing", info.missing().size());
            row.put("unreadable", info.unreadable());
            tracks += info.total();
        }
        out.put("tracks", tracks);
        ArrayNode said = out.putArray("warnings");
        warnings.forEach(said::add);
        return out.toString();
    }

    /**
     * The live log, held open.
     *
     * <p>No {@code Content-Length} and no buffering in front of it: the point of the route is
     * that a line reaches the page the moment it is printed.
     */
    private void watch(HttpExchange call) throws IOException {
        call.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
        call.getResponseHeaders().add("Cache-Control", "no-store");
        call.getResponseHeaders().add("X-Accel-Buffering", "no");
        call.sendResponseHeaders(200, 0);
        OutputStream body = call.getResponseBody();
        try {
            stream.join(body);
            // the thread has to stay here: the exchange is closed the moment it returns, and
            // with it the stream the page is reading
            while (!Thread.currentThread().isInterrupted()) {
                Thread.sleep(15_000);
                // a comment frame, so a page behind a proxy or a sleeping laptop notices a
                // connection that has died rather than waiting for a line that never comes
                body.write(": ping\n\n".getBytes(StandardCharsets.UTF_8));
                body.flush();
            }
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        } catch (IOException gone) {
            // the page went away, which is the normal end of this route
        } finally {
            stream.leave(body);
            call.close();
        }
    }

    private void startRun(HttpExchange call, String method) throws IOException {
        only(call, method, "POST");
        JsonNode asked = body(call);
        List<String> playlists = new ArrayList<>();
        for (JsonNode name : asked.path("playlists")) {
            String wanted = name.asText("").strip();
            if (!wanted.isEmpty()) {
                playlists.add(wanted);
            }
        }
        boolean dry = asked.path("dryRun").asBoolean(false);
        if (!jobs.start(playlists, dry)) {
            throw new Refused(409, "a run is already going");
        }
        stream.state(state(appDir, jobs));
        ObjectNode out = JSON.createObjectNode();
        out.put("started", true);
        out.put("dryRun", dry);
        text(call, 202, out.toString(), "application/json");
    }

    private void stopRun(HttpExchange call, String method) throws IOException {
        only(call, method, "POST");
        boolean asked = jobs.stop();
        stream.state(state(appDir, jobs));
        text(call, asked ? 202 : 200,
                JSON.createObjectNode().put("stopping", asked).toString(), "application/json");
    }

    // ------------------------------------------------------------------ playlists

    private void playlist(HttpExchange call, String method, String rawName) throws IOException {
        String name = java.net.URLDecoder.decode(rawName, StandardCharsets.UTF_8);
        Path file = playlistFile(name);
        if (method.equals("GET")) {
            if (!Files.isRegularFile(file)) {
                throw new Refused(404, "no playlist called " + name);
            }
            ObjectNode out = JSON.createObjectNode();
            String content = read(file);
            out.put("name", name);
            out.put("text", content);
            out.put("path", file.toString());
            found(out, Lint.check(content));
            text(call, 200, out.toString(), "application/json");
            return;
        }
        only(call, method, "PUT");
        if (jobs.running()) {
            throw new Refused(409, "a run is going; a playlist it is reading must not change "
                    + "under it");
        }
        JsonNode asked = body(call);
        String content = asked.path("text").asText("");
        ObjectNode out = JSON.createObjectNode();
        List<Lint.Found> found = Lint.check(content);
        found(out, found);
        if (Lint.errors(found) > 0 && !asked.path("anyway").asBoolean(false)) {
            // a line that would be searched for as something other than what it reads like.
            // The person can still insist; nothing here is a matter of taste
            out.put("saved", false);
            text(call, 400, out.toString(), "application/json");
            return;
        }
        backup(file).ifPresent(kept -> out.put("backup", kept.getFileName().toString()));
        write(file, content.endsWith("\n") || content.isEmpty() ? content : content + "\n");
        out.put("saved", true);
        out.put("name", name);
        out.put("lines", (int) content.lines().filter(line -> !line.isBlank()).count());
        text(call, 200, out.toString(), "application/json");
    }

    /** What the checker found, as the page shows it: the line, how bad it is, and why. */
    private static void found(ObjectNode out, List<Lint.Found> found) {
        ArrayNode said = out.putArray("found");
        for (Lint.Found one : found) {
            ObjectNode row = said.addObject();
            row.put("line", one.line());
            row.put("level", one.level().name().toLowerCase(Locale.ROOT));
            row.put("text", one.text());
            row.put("why", one.why());
        }
        out.put("errors", (int) Lint.errors(found));
    }

    /**
     * The file a name means, with the name treated as hostile.
     *
     * <p>The page sends the name back, so a {@code ..} in it would otherwise write a file
     * anywhere on the disk. Two checks rather than one: the characters are reduced to what a
     * playlist name may have, and the finished path is required to sit in the playlists folder.
     */
    private Path playlistFile(String name) {
        String cleaned = SAFE_NAME.matcher(name == null ? "" : name).replaceAll("").strip();
        if (cleaned.isEmpty() || cleaned.equals(".") || cleaned.contains("..")) {
            throw new Refused(400, "not a playlist name: " + name);
        }
        if (cleaned.toLowerCase(Locale.ROOT).endsWith(".txt")) {
            cleaned = cleaned.substring(0, cleaned.length() - 4);
        }
        Path folder = folder("playlists");
        Path file = folder.resolve(cleaned + ".txt").normalize();
        if (!file.startsWith(folder.normalize())) {
            throw new Refused(400, "not a playlist name: " + name);
        }
        return file;
    }

    // ------------------------------------------------------------------ the settings file

    private void config(HttpExchange call, String method) throws IOException {
        Path file = appDir.resolve(Settings.CONFIG_NAME);
        if (method.equals("GET")) {
            ObjectNode out = JSON.createObjectNode();
            out.put("text", Files.isRegularFile(file) ? read(file) : "");
            out.put("path", file.toString());
            ArrayNode said = out.putArray("warnings");
            complaints(Files.isRegularFile(file) ? read(file) : "").forEach(said::add);
            text(call, 200, out.toString(), "application/json");
            return;
        }
        only(call, method, "PUT");
        JsonNode asked = body(call);
        String content = asked.path("text").asText("");
        List<String> complaints = complaints(content);
        if (!complaints.isEmpty() && !asked.path("anyway").asBoolean(false)) {
            ObjectNode out = JSON.createObjectNode();
            out.put("saved", false);
            ArrayNode said = out.putArray("warnings");
            complaints.forEach(said::add);
            text(call, 400, out.toString(), "application/json");
            return;
        }
        ObjectNode out = JSON.createObjectNode();
        backup(file).ifPresent(kept -> out.put("backup", kept.getFileName().toString()));
        write(file, content.endsWith("\n") ? content : content + "\n");
        out.put("saved", true);
        ArrayNode said = out.putArray("warnings");
        complaints.forEach(said::add);
        stream.state(state(appDir, jobs));
        text(call, 200, out.toString(), "application/json");
    }

    /**
     * The settings as a form: categories, fields, and what the file says today.
     *
     * <p>A PUT carries only what changed, and each change is written into the file in place -
     * see {@link dev.litetook.sockseek.config.Rewrite}. The whole file is then parsed before
     * it is saved, exactly as the raw editor does, so the two ways in cannot disagree about
     * what is valid.
     */
    private void settings(HttpExchange call, String method) throws IOException {
        Path file = appDir.resolve(Settings.CONFIG_NAME);
        String text = Files.isRegularFile(file) ? read(file) : "";
        if (method.equals("GET")) {
            Config config = Settings.loadConfig(file, line -> { });
            text(call, 200, Fields.describe(Settings.parseYaml(text, line -> { }), config.sockseek()),
                    "application/json");
            return;
        }
        only(call, method, "PUT");
        JsonNode asked = body(call);
        JsonNode changes = asked.path("changes");
        if (!changes.isObject() || changes.isEmpty()) {
            throw new Refused(400, "nothing to change");
        }
        String written = text;
        List<String> touched = new ArrayList<>();
        java.util.Iterator<String> names = changes.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            String value = changes.path(name).asText("").strip();
            written = dev.litetook.sockseek.config.Rewrite.set(written, Fields.pathOf(name),
                    value, Fields.oldNamesOf(name));
            touched.add(name);
        }
        List<String> complaints = complaints(written);
        ObjectNode out = JSON.createObjectNode();
        ArrayNode said = out.putArray("warnings");
        complaints.forEach(said::add);
        if (!complaints.isEmpty() && !asked.path("anyway").asBoolean(false)) {
            out.put("saved", false);
            text(call, 400, out.toString(), "application/json");
            return;
        }
        backup(file).ifPresent(kept -> out.put("backup", kept.getFileName().toString()));
        write(file, written);
        out.put("saved", true);
        ArrayNode changed = out.putArray("changed");
        touched.forEach(changed::add);
        stream.log("--- settings changed from the page: " + String.join(", ", touched));
        stream.state(state(appDir, jobs));
        text(call, 200, out.toString(), "application/json");
    }

    /**
     * What the settings parser would say about this text, without writing it anywhere near the
     * real file.
     *
     * <p>Through a temporary file because that is the parser's own door, and going around it
     * with a second, web-only reader is exactly how the page would start accepting a file the
     * run then rejects.
     */
    private List<String> complaints(String content) {
        List<String> said = new ArrayList<>();
        Path probe = null;
        try {
            probe = Files.createTempFile("sockseek-config", ".yaml");
            Files.writeString(probe, content, StandardCharsets.UTF_8);
            // the parser names the file it read, and that is the temporary one. The page must
            // show the name the owner knows, or the complaint reads as being about some other
            // file he has never heard of
            String temporary = probe.getFileName().toString();
            Settings.loadConfig(probe, warning ->
                    said.add(warning.replace(temporary, Settings.CONFIG_NAME)));
        } catch (IOException cannotTry) {
            said.add("could not check the file: " + cannotTry.getMessage());
        } finally {
            try {
                if (probe != null) {
                    Files.deleteIfExists(probe);
                }
            } catch (IOException ignored) {
                // a temp file the system will clear itself
            }
        }
        return said;
    }

    // ------------------------------------------------------------------ the login

    /**
     * The login, written and never read back.
     *
     * <p>The page never receives it, not even masked: {@code loginSet} in the state says
     * whether there is one, and that is all a page needs to know.
     */
    private void login(HttpExchange call, String method) throws IOException {
        only(call, method, "POST");
        JsonNode asked = body(call);
        String user = asked.path("username").asText("").strip();
        String password = asked.path("password").asText("");
        if (user.isEmpty() || password.isBlank()) {
            throw new Refused(400, "both a username and a password are needed");
        }
        try {
            Settings.writeLogin(appDir, user, password);
        } catch (IOException cannotWrite) {
            throw new Refused(500, "could not write " + Settings.LOCAL_CONFIG_NAME + ": "
                    + cannotWrite.getMessage());
        }
        stream.log("--- login saved to " + Settings.LOCAL_CONFIG_NAME);
        stream.state(state(appDir, jobs));
        text(call, 200, JSON.createObjectNode().put("saved", true).toString(),
                "application/json");
    }

    /**
     * Whether the login works, without downloading anything.
     *
     * <p>One search, asked with {@code --print results}, which is how this was measured on
     * 2026-10-19: sockseek logs {@code Logging in as X} and then {@code Logged in as X}
     * before it searches, and says so plainly when it cannot. The search itself is thrown
     * away - the point is the two lines above it.
     *
     * <p>It costs one search out of the account's budget, and the answer says so.
     */
    private void loginCheck(HttpExchange call, String method) throws IOException {
        only(call, method, "POST");
        if (jobs.running()) {
            throw new Refused(409, "a run is going; its own log already says whether the "
                    + "login worked");
        }
        if (Settings.missingLogin(appDir)) {
            throw new Refused(400, "there is no login to check yet");
        }
        Config config = Settings.loadConfig(appDir.resolve(Settings.CONFIG_NAME), line -> { });
        java.util.Map<String, Path> folders = Settings.configPaths(config, appDir);
        Path exe = folders.get("sockseek_bin");
        if (exe == null || !Files.isRegularFile(exe)) {
            throw new Refused(400, "sockseek is not here yet, so there is nothing to ask");
        }
        Path work = folders.get("work");
        Files.createDirectories(work);
        Path list = work.resolve("login-check.list.txt");
        Files.writeString(list, "s:\"title=sockseek cli login check\"\n",
                StandardCharsets.UTF_8);
        Path log = work.resolve("login-check.log");
        Files.deleteIfExists(log);
        Trace.Tail trace = new Trace.Tail(log, heard -> {
            if (heard.aboutLogin()) {
                stream.log("auth: " + (heard.login() == Trace.Login.IN
                        ? "logged in as " + heard.about()
                        : "LOGIN FAILED - " + heard.about()));
            }
        });
        List<String> command = Runner.buildCommand(exe, list, work.resolve("login-check"),
                new Options(-1, true, 1, null, -1), log, List.of("--no-progress"),
                Runner.INDEX_NAME,
                Settings.effectiveConf(appDir, work, line -> { }, null));
        stream.log("--- checking the login with one search (it costs one from the limit)");
        Runner.runProcess(command, appDir, CHECK_SECONDS, stream::log, null, 0, null, trace);
        ObjectNode out = JSON.createObjectNode();
        out.put("login", trace.login().name().toLowerCase(Locale.ROOT));
        out.put("about", trace.account());
        out.put("cost", "one search");
        text(call, 200, out.toString(), "application/json");
    }

    /** Long enough for a search to come back, short enough not to hold the page open. */
    private static final double CHECK_SECONDS = 120;

    // ------------------------------------------------------------------ add by link

    /**
     * A link into a playlist file, or one track into {@code unsorted}.
     *
     * <p>The same two paths the menu offers, and the same code: a playlist link goes through
     * {@code --import}, a track link through the option-4 path. Nothing is downloaded here -
     * the file is written, and the Run page is where a download starts.
     */
    private void link(HttpExchange call, String method) throws IOException {
        only(call, method, "POST");
        JsonNode asked = body(call);
        String url = asked.path("url").asText("").strip();
        if (url.isEmpty() || !Links.isLink(url)) {
            throw new Refused(400, "that is not a link");
        }
        if (jobs.running()) {
            throw new Refused(409, "a run is going; reading a link can wait for it");
        }
        boolean asTrack = asked.path("track").asBoolean(false);
        List<String> printed = new ArrayList<>();
        Consumer<String> out = line -> {
            printed.add(line);
            stream.log(line);
        };
        int code;
        if (asTrack) {
            code = App.addTrack(url, appDir, out);
        } else {
            List<String> argv = new ArrayList<>(List.of("--import", url));
            String name = asked.path("as").asText("").strip();
            if (!name.isEmpty()) {
                argv.add("--as");
                argv.add(name);
            }
            if (asked.path("overwrite").asBoolean(false)) {
                argv.add("--overwrite");
            }
            code = App.run(argv.toArray(String[]::new), null, out, App.Wiring.standard(appDir));
        }
        ObjectNode answer = JSON.createObjectNode();
        answer.put("code", code);
        answer.put("track", asTrack);
        ArrayNode said = answer.putArray("printed");
        printed.forEach(said::add);
        stream.state(state(appDir, jobs));
        text(call, code == 0 ? 200 : 400, answer.toString(), "application/json");
    }

    // ------------------------------------------------------------------ the plumbing

    private Path folder(String key) {
        Config config = Settings.loadConfig(appDir.resolve(Settings.CONFIG_NAME), line -> { });
        return Settings.configPaths(config, appDir).get(key);
    }

    /** A copy beside the file, the same {@code .bak-<stamp>} the owner's own files carry. */
    private static Optional<Path> backup(Path file) {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        Path kept = file.resolveSibling(file.getFileName() + ".bak-"
                + LocalDateTime.now().format(STAMP));
        try {
            Files.copy(file, kept, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return Optional.of(kept);
        } catch (IOException cannotCopy) {
            throw new Refused(500, "could not take a backup of " + file.getFileName() + ": "
                    + cannotCopy.getMessage() + ". Nothing was written");
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException cannotRead) {
            throw new Refused(500, "could not read " + file.getFileName() + ": "
                    + cannotRead.getMessage());
        }
    }

    private static void write(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException cannotWrite) {
            throw new Refused(500, "could not write " + file.getFileName() + ": "
                    + cannotWrite.getMessage());
        }
    }

    private static JsonNode body(HttpExchange call) throws IOException {
        byte[] bytes = call.getRequestBody().readAllBytes();
        if (bytes.length == 0) {
            return JSON.createObjectNode();
        }
        try {
            return JSON.readTree(bytes);
        } catch (IOException notJson) {
            throw new Refused(400, "the body is not JSON");
        }
    }

    private static void only(HttpExchange call, String method, String wanted) {
        if (!method.equals(wanted)) {
            throw new Refused(405, method + " is not how this route is used; " + wanted + " is");
        }
    }

    static void text(HttpExchange call, int code, String body, String type) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        call.getResponseHeaders().add("Content-Type", type + "; charset=utf-8");
        call.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = call.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void fail(HttpExchange call, int code, String why) throws IOException {
        text(call, code, JSON.createObjectNode().put("error", why).toString(),
                "application/json");
    }

    /** A refusal with the code the page should see. Carried as an exception to keep routes short. */
    private static final class Refused extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final int code;

        Refused(int code, String why) {
            super(why);
            this.code = code;
        }
    }
}
