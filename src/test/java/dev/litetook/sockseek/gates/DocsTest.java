package dev.litetook.sockseek.gates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The manuals and the tool say the same thing.
 *
 * <p>A manual that documents an option the tool does not accept, or a tool that accepts one
 * no manual mentions, is a bug of the same kind as a wrong line of output: this text is the
 * interface. It was written after the manual was found to still describe a {@code FAILED
 * n/m} summary line that the tool had never printed (2026-10-15).
 */
class DocsTest {

    /** Any {@code --option} named anywhere in a manual. */
    private static final Pattern OPTION =
            Pattern.compile("--[a-z][a-z0-9]*(?:-[a-z0-9]+)*");

    /**
     * An option a help text LISTS, as opposed to one it mentions in passing.
     *
     * <p>Both picocli and the manuals print an option at the start of its own line or inside
     * the brackets of a usage line, while an example like {@code --sockseek-args="--desperate"}
     * sits in the middle of a sentence. Reading every token as an option made the first
     * version of this report {@code --desperate} as a flag of the tool, which it is not.
     */
    private static final Pattern LISTED = Pattern.compile(
            "(?:^\\s+|[\\[(]\\s*)(?:-[A-Za-z],\\s+)?(--[a-z][a-z0-9]*(?:-[a-z0-9]+)*)",
            Pattern.MULTILINE);

    private static final Pattern ANSI = Pattern.compile("\u001b\\[[0-9;]*m");

    /** Not ours: they are named in the manuals because that is where you pass them. */
    private static final Set<String> FOREIGN = Set.of(
            // sockseek's own options
            "--artist-maybe-wrong", "--concurrent-searches", "--desperate", "--extract-artist",
            "--index-path", "--input-type", "--max-bitrate", "--max-retries",
            "--max-stale-time", "--min-bitrate", "--mock-files-dir", "--name-format",
            "--no-config", "--no-skip-existing", "--output-dir", "--pref-min-bitrate",
            "--print", "--remove-brackets", "--remove-ft", "--searches-per-time",
            "--searches-renew-time", "--search-timeout", "--strict-conditions",
            "--pref-max-bitrate", "--pref-format", "--format", "--strict-title", "--log-file",
            "--on-complete", "--progress-json", "--skip-not-found",
            // yt-dlp, for the SoundCloud import
            "--flat-playlist", "--ignore-errors", "--no-warnings", "--skip-download",
            // the build and the test runner
            "--offline", "--tests", "--record",
            // yt-dlp again, named as the option you pass it
            "--cookies-from-browser-example");

    /**
     * Accepted but deliberately absent from {@code --help}, so a manual may name what the
     * tool will not list: {@code --no-explain} is a no-op kept because it is in the owner's
     * fingers.
     */
    private static final Set<String> HIDDEN = Set.of("--no-explain");

    @Test
    void everyOptionIsDocumentedAndEveryDocumentedOptionExists() throws IOException {
        Set<String> accepted = new LinkedHashSet<>();
        accepted.addAll(listed(help("--help")));
        accepted.addAll(listed(engineHelp()));

        Set<String> documented = new LinkedHashSet<>();
        for (Path manual : List.of(Path.of("README.md"), Path.of("docs", "README.md"))) {
            assertTrue(Files.isRegularFile(manual), manual + " is missing");
            Matcher found = OPTION.matcher(Files.readString(manual, StandardCharsets.UTF_8));
            while (found.find()) {
                documented.add(found.group());
            }
        }

        Set<String> lying = new TreeSet<>(documented);
        lying.removeAll(accepted);
        lying.removeAll(FOREIGN);
        lying.removeAll(HIDDEN);
        assertEquals(Set.of(), lying, "documented, but the tool does not accept it");

        Set<String> quiet = new TreeSet<>(accepted);
        quiet.removeAll(documented);
        assertEquals(Set.of(), quiet, "accepted, but no manual mentions it");
    }

    private static Set<String> listed(String text) {
        Set<String> found = new LinkedHashSet<>();
        Matcher match = LISTED.matcher(ANSI.matcher(text).replaceAll(""));
        while (match.find()) {
            found.add(match.group(1));
        }
        return found;
    }

    private static String help(String... args) {
        return String.join("\n", Gates.run(Path.of("."), args));
    }

    /** The engine has its own command line, and its own options to keep honest. */
    private static String engineHelp() {
        return String.join("\n", Gates.runClass("dev.litetook.sockseek.cli.Cli", "--help"));
    }
}
