package dev.litetook.sockseek.batch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** The run number, the log file, and the command line as Windows reads it. */
public final class Runs {

    /** In logs/: the last run number used, a single integer. */
    public static final String SEQ_NAME = "run_seq.txt";
    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private static final Pattern LOG_NAME = Pattern.compile("run_(\\d+)_.*\\.log");
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    private Runs() {
    }

    /** 0043 for run 43; numbers past 9999 just get longer. */
    public static String label(int number) {
        return String.format("%04d", number);
    }

    /** What one run produced: its number and anything worth saying about the counter. */
    public record Numbered(int number, List<String> warnings) {
    }

    /**
     * The next number from logs/run_seq.txt, written back at once.
     *
     * <p>Never fails: a missing or garbled file is rebuilt from the highest run log name with a
     * warning, and a counter that cannot be written gives number 0 and the run goes on.
     */
    public static Numbered nextRunNumber(Path logsDir) {
        Path seq = logsDir.resolve(SEQ_NAME);
        List<String> warnings = new ArrayList<>();
        Integer last = null;
        try {
            String raw = Files.readString(seq, StandardCharsets.UTF_8).strip();
            if (DIGITS.matcher(raw).matches()) {
                last = Integer.valueOf(raw);
            }
        } catch (IOException | NumberFormatException noCounter) {
            last = null;
        }
        if (last == null) {
            int highest = 0;
            try (Stream<Path> logs = Files.list(logsDir)) {
                for (Path path : logs.toList()) {
                    Matcher found = LOG_NAME.matcher(path.getFileName().toString());
                    if (found.matches()) {
                        highest = Math.max(highest, Integer.parseInt(found.group(1)));
                    }
                }
            } catch (IOException | NumberFormatException noLogs) {
                highest = Math.max(highest, 0);
            }
            last = highest;
            warnings.add("WARN " + SEQ_NAME + " is missing or not a number, run counter rebuilt "
                    + "from the log file names in " + logsDir + ": highest number found "
                    + highest);
        }
        int number = last + 1;
        try {
            Files.writeString(seq, number + "\n", StandardCharsets.UTF_8);
        } catch (IOException cannotWrite) {
            warnings.add("WARN cannot write " + seq + " (" + cannotWrite.getMessage()
                    + "), using run number 0");
            number = 0;
        }
        return new Numbered(number, List.copyOf(warnings));
    }

    public static Path logPath(Path logsDir, int number, LocalDateTime stamp) {
        return logsDir.resolve("run_" + label(number) + "_" + STAMP.format(stamp) + ".log");
    }

    /** Prints the line and appends it to the run log. Logging must never stop a download. */
    public static Consumer<String> logger(Path logPath, Consumer<String> out) {
        return message -> {
            String line = "[" + CLOCK.format(LocalDateTime.now()) + "] " + message;
            out.accept(line);
            try {
                Files.writeString(logPath, line + "\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException cannotLog) {
                // a run is worth more than its log
            }
        };
    }

    /**
     * The command as a Windows command line, the way Python's subprocess.list2cmdline writes
     * it - so the dry-run line of both implementations is the same text, down to the quoting.
     */
    public static String commandLine(List<String> command) {
        StringBuilder result = new StringBuilder();
        for (String argument : command) {
            if (!result.isEmpty()) {
                result.append(' ');
            }
            StringBuilder backslashes = new StringBuilder();
            boolean quote = argument.isEmpty() || argument.contains(" ") || argument.contains("\t");
            if (quote) {
                result.append('"');
            }
            for (char character : argument.toCharArray()) {
                if (character == '\\') {
                    backslashes.append(character);
                } else if (character == '"') {
                    result.append("\\".repeat(backslashes.length() * 2));
                    backslashes.setLength(0);
                    result.append("\\\"");
                } else {
                    result.append(backslashes);
                    backslashes.setLength(0);
                    result.append(character);
                }
            }
            result.append(backslashes);
            if (quote) {
                result.append(backslashes).append('"');
            }
        }
        return result.toString();
    }
}
