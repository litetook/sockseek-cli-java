package dev.litetook.sockseek.web;

import dev.litetook.sockseek.App;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The run the page started: one at a time, on a thread of its own.
 *
 * <p>It is the same run the console does. {@link App#run} is called with the same arguments the
 * owner would type, so the price, the confirmation that {@code --yes} answers, the search brake
 * between playlists, the passes, the cache and the silence watchdog are all the ones that were
 * measured - there is no second engine here to drift from the first.
 *
 * <p>Two things had to be arranged for a browser to watch it.
 *
 * <p><b>The output.</b> The front end prints through a {@link java.util.function.Consumer}, but
 * the engine prints through {@code System.out}, which it was handed long before any of this
 * existed. So for the length of a run {@code System.out} is a stream that splits every line
 * three ways - the console, the session log it already went to, and the page - and is put back
 * afterwards. Nothing in the engine knows.
 *
 * <p><b>Stop.</b> Interrupting this thread is enough: {@code Runner} waits on sockseek with
 * {@code waitFor(timeout)}, and an interrupt there already kills the process tree, which is
 * how the timeout and the watchdog stop it too. The playlist loop in {@code App} then sees the
 * interrupt and says so rather than marching through the rest of the list.
 */
final class Jobs {

    private final Path appDir;
    private final Stream stream;

    private Thread worker;
    private volatile boolean dryRun;
    private volatile boolean stopping;

    Jobs(Path appDir, Stream stream) {
        this.appDir = appDir;
        this.stream = stream;
    }

    synchronized boolean running() {
        return worker != null && worker.isAlive();
    }

    boolean dry() {
        return dryRun;
    }

    boolean stopping() {
        return stopping;
    }

    /**
     * Start a run over the named playlists, or over all of them when the list is empty.
     *
     * @return false when a run is already going, which the page reports as a refusal
     */
    synchronized boolean start(List<String> playlists, boolean dry) {
        if (running()) {
            return false;
        }
        List<String> argv = new ArrayList<>();
        if (playlists.isEmpty()) {
            argv.add("--all");
        } else {
            argv.add("--only");
            argv.add(String.join(",", playlists));
        }
        argv.add("--yes");
        if (dry) {
            argv.add("--dry-run");
        }
        dryRun = dry;
        stopping = false;
        worker = new Thread(() -> run(argv), "sockseek-web-run");
        worker.setDaemon(true);
        worker.start();
        return true;
    }

    /** Ask the run to stop. It is not stopped when this returns; the log says when it is. */
    synchronized boolean stop() {
        if (!running()) {
            return false;
        }
        stopping = true;
        stream.log("");
        stream.log("--- stop asked for from the web page");
        worker.interrupt();
        return true;
    }

    private void run(List<String> argv) {
        PrintStream was = System.out;
        PrintStream tap = new PrintStream(new Lines(was, stream::log), true,
                StandardCharsets.UTF_8);
        System.setOut(tap);
        int code = 2;
        try {
            // ask == null: nobody is at a keyboard here. The front end takes that to mean
            // "say where the login goes and stop" rather than hanging on a question
            code = App.run(argv.toArray(String[]::new), null, line -> {
                was.println(line);
                stream.log(line);
            }, App.Wiring.standard(appDir));
        } catch (RuntimeException broke) {
            stream.log("ERROR the run broke: " + broke);
        } finally {
            tap.flush();
            System.setOut(was);
            stream.log("");
            stream.log(stopping ? "--- stopped, exit code " + code
                    : "--- finished, exit code " + code);
            stream.state(Api.state(appDir, this));
        }
    }

    /**
     * A stream that passes bytes through and hands whole lines to a watcher.
     *
     * <p>Lines rather than bytes because an event is a line: half a line in the browser, with
     * the other half arriving when the next flush happens, reads as a bug. A carriage return
     * ends a line too - sockseek's own progress display rewrites one line with them.
     */
    private static final class Lines extends OutputStream {

        private final OutputStream pass;
        private final java.util.function.Consumer<String> watcher;
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

        Lines(OutputStream pass, java.util.function.Consumer<String> watcher) {
            this.pass = pass;
            this.watcher = watcher;
        }

        @Override
        public void write(int oneByte) throws java.io.IOException {
            pass.write(oneByte);
            if (oneByte == '\n' || oneByte == '\r') {
                flushLine();
                return;
            }
            pending.write(oneByte);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws java.io.IOException {
            for (int at = offset; at < offset + length; at++) {
                write(bytes[at]);
            }
        }

        @Override
        public void flush() throws java.io.IOException {
            pass.flush();
        }

        private void flushLine() {
            if (pending.size() == 0) {
                return;
            }
            String line = pending.toString(StandardCharsets.UTF_8);
            pending.reset();
            watcher.accept(line);
        }
    }
}
