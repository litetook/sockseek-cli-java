package dev.litetook.sockseek.process;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * sockseek's own log, read while it is being written.
 *
 * <p>Two questions can only be answered from here, and the owner asked both on 2026-10-19:
 * <b>did the login work</b>, and <b>why was a track refused</b>. sockseek's console display
 * goes straight to the real console (Runner inherits the handles), so nothing of it can be
 * read back; its {@code --log-file} can, and since 2026-10-19 it is written on every run
 * rather than only with {@code --verbose}.
 *
 * <p>Every line below was measured on sockseek 3.0.5.0 on 2026-10-19, in a sandbox, with an
 * account that exists nowhere:
 *
 * <pre>
 * [info]  [soulseek] Logging in as someone..
 * [debug] [soulseek] Logged in as someone
 * [debug] [soulseek] 45 results found: ping test
 * [info]  [jobs] [4] SongJob: searching: ping test
 * </pre>
 *
 * <p>The failure wordings come from the binary's own strings, read out of it the same day:
 * {@code Soulseek login failed:}, {@code Initial Soulseek login failed.}, and the one worth
 * knowing about, {@code Soulseek server kicked this client, probably because the same account
 * logged in elsewhere}.
 *
 * <p>One measurement decides how the candidate count is read: with {@code --min-bitrate 9999}
 * the same search reported 22 results where it had reported 45, so the number is what is left
 * <b>after</b> this pass's conditions - which is exactly the number worth knowing. A pass that
 * reports none and a later pass that reports some is the difference between "nobody has it"
 * and "nobody has it at this quality".
 */
public final class Trace {

    /** What the log said about the account. */
    public enum Login {
        /** Nothing about a login has been read yet. */
        UNKNOWN,
        /** sockseek said it logged in. */
        IN,
        /** sockseek said the login failed, or that the server threw this client off. */
        OUT
    }

    private static final Pattern LOGGED_IN =
            Pattern.compile("\\[soulseek]\\s+Logged in as (.+?)\\s*$");
    private static final Pattern RESULTS =
            Pattern.compile("\\[soulseek]\\s+(\\d+) results found:\\s*(.+?)\\s*$");
    /** The wordings of a login that did not happen, from sockseek's own strings. */
    private static final List<String> REFUSED = List.of(
            "soulseek login failed",
            "initial soulseek login failed",
            "failed to ensure soulseek connection and login",
            "kicked this client");

    private Trace() {
    }

    /** What one line of the log says, or empty when it says nothing we read. */
    public static Optional<Said> parse(String line) {
        String text = line == null ? "" : line.strip();
        if (text.isEmpty()) {
            return Optional.empty();
        }
        String low = text.toLowerCase(Locale.ROOT);
        for (String refused : REFUSED) {
            if (low.contains(refused)) {
                return Optional.of(Said.login(Login.OUT, afterLevel(text)));
            }
        }
        Matcher in = LOGGED_IN.matcher(text);
        if (in.find()) {
            return Optional.of(Said.login(Login.IN, in.group(1)));
        }
        return Optional.empty();
    }

    /** The message without the timestamp and the two bracketed tags in front of it. */
    private static String afterLevel(String line) {
        int at = line.indexOf("] [");
        int end = at < 0 ? -1 : line.indexOf(']', at + 2);
        return end < 0 ? line : line.substring(end + 1).strip();
    }

    /**
     * One thing the log said about the account.
     *
     * @param about the account name for {@link Login#IN}, the whole complaint for
     *              {@link Login#OUT}
     */
    public record Said(Login login, String about) {

        static Said login(Login state, String about) {
            return new Said(state, about);
        }

        public boolean aboutLogin() {
            return login != Login.UNKNOWN;
        }
    }

    /**
     * The log, followed line by line while sockseek writes it.
     *
     * <p>Built like {@link Events.Tail} and polled by the same loop: the file is opened fresh
     * each poll, read from where the last poll stopped, and a half-written last line is left
     * for next time. A log that never appears - an older sockseek, a folder that cannot be
     * written - simply says nothing, because a watcher must never be able to break a download.
     */
    public static final class Tail {

        private final Path file;
        private final Consumer<Said> onSaid;
        private long read;
        private String pending = "";
        private Login login = Login.UNKNOWN;
        private String account = "";

        public Tail(Path file, Consumer<Said> onSaid) {
            this.file = file;
            this.onSaid = onSaid;
        }

        /** Start again from the top: a retry writes a new log over the old one. */
        public void restart() {
            read = 0;
            pending = "";
        }

        public Login login() {
            return login;
        }

        /** The account sockseek said it logged in as, or the complaint when it did not. */
        public String account() {
            return account;
        }

        public void poll() {
            if (file == null || !Files.isReadable(file)) {
                return;
            }
            try (RandomAccessFile open = new RandomAccessFile(file.toFile(), "r")) {
                long length = open.length();
                if (length < read) {
                    restart();   // the file was replaced under us
                }
                open.seek(read);
                byte[] bytes = new byte[(int) Math.min(length - read, 1 << 20)];
                int got = open.read(bytes);
                if (got <= 0) {
                    return;
                }
                read += got;
                String text = pending + new String(bytes, 0, got, StandardCharsets.UTF_8);
                int lastBreak = text.lastIndexOf('\n');
                if (lastBreak < 0) {
                    pending = text;
                    return;
                }
                pending = text.substring(lastBreak + 1);
                for (String line : text.substring(0, lastBreak).split("\n")) {
                    parse(line).ifPresent(said -> {
                        if (said.aboutLogin()) {
                            login = said.login();
                            account = said.about() == null ? "" : said.about();
                        }
                        onSaid.accept(said);
                    });
                }
            } catch (IOException cannotRead) {
                // a log we cannot follow costs us two explanations, not the download
            }
        }
    }
}
