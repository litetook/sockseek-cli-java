package dev.litetook.sockseek.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * The same tool, in a browser, on this machine only.
 *
 * <p>Asked for on 2026-10-19: start a run and watch the log, edit the playlists, read a link,
 * change the settings, enter the login. The console stays the way in that is always there -
 * "консольне сто відсотків, як базове" - and this is a second door into the same engine, not
 * a replacement for it.
 *
 * <p>It needs nothing installed. {@code jdk.httpserver} is inside the Java that {@code run.cmd}
 * already fetches (checked against the pinned JRE on 2026-10-19), the page is three files in
 * the jar, and there is no container and no virtualisation anywhere in it - which is the point,
 * because the machine it has to work on may have none.
 *
 * <h2>Why it is safe enough to be worth having</h2>
 *
 * <p>A port on localhost is not private. Any page open in the same browser can send requests to
 * {@code 127.0.0.1}, so without a guard a tab on some other site could start a download, read
 * the playlists or rewrite the settings. Three things stand in the way, and all three are
 * needed:
 *
 * <ul>
 *   <li>the server binds the <b>loopback address only</b>, so nothing off this machine reaches
 *   it at all;
 *   <li>every request carries a <b>token</b> made at startup and printed in the URL. Another
 *   site cannot read it - it is in this page's address, and reading across origins is what
 *   browsers forbid - so it cannot forge a request that counts;
 *   <li>the <b>Host header</b> has to be the loopback name, which is what stops DNS rebinding:
 *   a name that resolves to 127.0.0.1 would otherwise let an outside page talk to this server
 *   with the browser's own permission.
 * </ul>
 *
 * <p>What it is not: an interface to put on a network. There is one token, no accounts, and no
 * TLS, because the traffic never leaves the machine.
 */
public final class Web {

    /** The port the owner agreed on, 2026-10-19. */
    public static final int DEFAULT_PORT = 8787;

    /** How many requests can be in flight: the page itself, its live log, and room to spare. */
    private static final int THREADS = 6;

    private static final Map<String, String> TYPES = Map.of(
            ".html", "text/html",
            ".css", "text/css",
            ".js", "application/javascript",
            ".svg", "image/svg+xml",
            ".ico", "image/x-icon");

    private Web() {
    }

    /** A server that is up: the address to open, and the way to stop it again. */
    public record Running(HttpServer server, String url, int port) implements AutoCloseable {

        @Override
        public void close() {
            server.stop(0);
        }
    }

    /**
     * Serve until the process is stopped.
     *
     * @param port 0 takes any free port, which is what a test wants
     * @param open whether to send the browser to the page
     * @return 0 when the server stopped on its own, 2 when it could not start
     */
    public static int serve(Path appDir, int port, Consumer<String> out, boolean open) {
        Optional<Running> up = start(appDir, port, out, open);
        if (up.isEmpty()) {
            return 2;
        }
        try {
            // the server runs on its own threads, so this one only has to stay alive
            Thread.currentThread().join();
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        }
        up.get().close();
        return 0;
    }

    /**
     * Start serving and come straight back.
     *
     * <p>Separate from {@link #serve} so that a test can start the page, ask it things and stop
     * it again, which a method that parks the thread forever cannot be asked to do.
     */
    public static Optional<Running> start(Path appDir, int port, Consumer<String> out,
                                          boolean open) {
        String token = HexFormat.of().formatHex(bytes(16));
        Stream stream = new Stream();
        Jobs jobs = new Jobs(appDir, stream);
        Api api = new Api(appDir, jobs, stream);
        HttpServer server;
        try {
            server = HttpServer.create(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        } catch (IOException cannotBind) {
            out.accept("The web page cannot start on port " + port + ": "
                    + cannotBind.getMessage());
            out.accept("Something else is on that port. Pass --web-port <number> for another "
                    + "one, or carry on in the console.");
            return Optional.empty();
        }
        int bound = server.getAddress().getPort();
        String url = "http://127.0.0.1:" + bound + "/?t=" + token;
        server.createContext("/", call -> {
            try {
                boolean isApi = call.getRequestURI().getPath().startsWith("/api/");
                if (!allowed(call, token, bound, isApi)) {
                    return;
                }
                if (isApi) {
                    api.handle(call);
                } else {
                    file(call);
                }
            } catch (IOException | RuntimeException broke) {
                // one bad request must not take the server down with it
                try {
                    Api.text(call, 500, "{\"error\":\"" + broke.getClass().getSimpleName()
                            + "\"}", "application/json");
                } catch (IOException gone) {
                    call.close();
                }
            }
        });
        server.setExecutor(Executors.newFixedThreadPool(THREADS, runnable -> {
            Thread thread = new Thread(runnable, "sockseek-web");
            thread.setDaemon(true);
            return thread;
        }));
        server.start();
        out.accept("");
        out.accept("The page is at");
        out.accept("    " + url);
        out.accept("");
        out.accept("That address carries the key to it, so paste it as it is. The console here "
                + "keeps working;");
        out.accept("Ctrl+C stops the page and the tool together.");
        if (open) {
            browse(url, out);
        }
        return Optional.of(new Running(server, url, bound));
    }

    /**
     * Whether this request is from the page and not from somewhere else.
     *
     * <p>Answers the request itself when it is not, so the caller can simply stop.
     *
     * <p>The key is asked of the routes that do something and not of the three files the page
     * is made of. It has to be that way round: a browser sends no key of ours when it fetches
     * the stylesheet and the script named in the HTML, so a key demanded there would leave the
     * page bare - which is exactly what happened the first time this was tried, on 2026-10-19.
     * Nothing is given away by it. The three files are the same for everyone, they hold no
     * setting, no playlist and no login, and a page opened without the key in its address can
     * do nothing at all: every route that reads or changes anything is behind the key.
     */
    private static boolean allowed(HttpExchange call, String token, int port, boolean isApi)
            throws IOException {
        String host = header(call, "Host").toLowerCase(Locale.ROOT);
        boolean loopback = host.equals("127.0.0.1:" + port) || host.equals("localhost:" + port)
                || host.equals("[::1]:" + port);
        if (!loopback) {
            Api.text(call, 403, "{\"error\":\"this page is only served to this machine\"}",
                    "application/json");
            return false;
        }
        if (isApi && !token.equals(given(call))) {
            Api.text(call, 403, "{\"error\":\"the key in the address is missing or wrong. "
                    + "Start the tool with --web again and use the address it prints\"}",
                    "application/json");
            return false;
        }
        return true;
    }

    /** The token, from the query string or from the header the page's own requests carry. */
    private static String given(HttpExchange call) {
        String header = header(call, "X-Sockseek-Key");
        if (!header.isEmpty()) {
            return header;
        }
        String query = call.getRequestURI().getQuery();
        if (query == null) {
            return "";
        }
        for (String pair : query.split("&")) {
            if (pair.startsWith("t=")) {
                return java.net.URLDecoder.decode(pair.substring(2), StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    private static String header(HttpExchange call, String name) {
        List<String> values = call.getRequestHeaders().get(name);
        return values == null || values.isEmpty() ? "" : values.get(0).strip();
    }

    /** The page itself: three files, inside the jar, served from the classpath. */
    private static void file(HttpExchange call) throws IOException {
        String path = call.getRequestURI().getPath();
        String wanted = path.equals("/") ? "index.html" : path.substring(1);
        // nothing reaches outside the one folder the page lives in
        if (wanted.contains("..") || wanted.contains("/") || wanted.contains("\\")) {
            Api.text(call, 404, "{\"error\":\"no such file\"}", "application/json");
            return;
        }
        try (InputStream content = Web.class.getResourceAsStream("/web/" + wanted)) {
            if (content == null) {
                Api.text(call, 404, "{\"error\":\"no such file: " + wanted + "\"}",
                        "application/json");
                return;
            }
            byte[] bytes = content.readAllBytes();
            int dot = wanted.lastIndexOf('.');
            String type = dot < 0 ? "application/octet-stream"
                    : TYPES.getOrDefault(wanted.substring(dot), "application/octet-stream");
            call.getResponseHeaders().add("Content-Type", type + "; charset=utf-8");
            // the files change when the jar does, and the page is served from a jar that
            // may have just been replaced by an update
            call.getResponseHeaders().add("Cache-Control", "no-store");
            call.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = call.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    /**
     * Send the machine's browser to the page.
     *
     * <p>By the operating system's own opener rather than {@code java.awt.Desktop}: a headless
     * JRE has no desktop toolkit, and a tool that refuses to start a web page because AWT is
     * missing would be absurd. A failure here is worth one line and nothing more - the address
     * is printed above it either way.
     */
    private static void browse(String url, Consumer<String> out) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        List<String> command = os.contains("win")
                ? List.of("rundll32", "url.dll,FileProtocolHandler", url)
                : os.contains("mac") ? List.of("open", url) : List.of("xdg-open", url);
        try {
            new ProcessBuilder(command).start();
        } catch (IOException cannotOpen) {
            out.accept("(could not open the browser here: " + cannotOpen.getMessage()
                    + " - paste the address above into it)");
        }
    }

    private static byte[] bytes(int many) {
        byte[] made = new byte[many];
        new SecureRandom().nextBytes(made);
        return made;
    }
}
