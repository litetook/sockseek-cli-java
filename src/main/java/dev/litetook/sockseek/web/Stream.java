package dev.litetook.sockseek.web;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The live log, as server-sent events.
 *
 * <p>One long GET per open page, held open for the whole run. SSE rather than a socket because
 * the traffic only ever goes one way - the page says nothing back over it - and because it is
 * plain HTTP: no library, no handshake, and a browser reconnects by itself.
 *
 * <p>The last lines are kept so that a page opened in the middle of a run, or reloaded during
 * one, shows what has happened so far instead of an empty box. The limit is there because a
 * playlist of 1300 tracks prints tens of thousands of lines and nobody scrolls back that far.
 */
final class Stream {

    /** Lines kept for a page that arrives late. About one long playlist's worth. */
    static final int HISTORY = 2000;

    private final Deque<String> history = new ArrayDeque<>();
    private final List<OutputStream> listeners = new ArrayList<>();

    /** A page joins. It is handed the history first, then every line as it happens. */
    synchronized void join(OutputStream client) throws IOException {
        for (String line : history) {
            write(client, "log", line);
        }
        client.flush();
        listeners.add(client);
    }

    synchronized void leave(OutputStream client) {
        listeners.remove(client);
    }

    /** One line of the run's output, to everyone watching. */
    synchronized void log(String line) {
        send("log", line);
    }

    /**
     * The run's state as JSON, which the page uses to enable and disable its buttons.
     *
     * <p>Not kept in the history: a state from three minutes ago would tell a page that has
     * just joined the wrong thing, and the page asks for the state once when it loads anyway.
     */
    synchronized void state(String json) {
        broadcast("state", json);
    }

    private void send(String event, String data) {
        history.addLast(data);
        while (history.size() > HISTORY) {
            history.removeFirst();
        }
        broadcast(event, data);
    }

    private void broadcast(String event, String data) {
        listeners.removeIf(client -> {
            try {
                write(client, event, data);
                client.flush();
                return false;
            } catch (IOException gone) {
                // the page was closed or reloaded; dropping it here is the whole clean-up
                return true;
            }
        });
    }

    /**
     * One event in the wire format.
     *
     * <p>A newline inside the data would end the event early, so a line is sent as several
     * {@code data:} fields, which the browser joins back with newlines - that is the protocol's
     * own answer and the only one that survives a track title with a line break in it.
     */
    private static void write(OutputStream client, String event, String data) throws IOException {
        StringBuilder frame = new StringBuilder("event: ").append(event).append('\n');
        for (String part : data.split("\n", -1)) {
            frame.append("data: ").append(part).append('\n');
        }
        frame.append('\n');
        client.write(frame.toString().getBytes(StandardCharsets.UTF_8));
    }
}
