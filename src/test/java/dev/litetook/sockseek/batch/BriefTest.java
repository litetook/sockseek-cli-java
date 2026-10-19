package dev.litetook.sockseek.batch;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.litetook.sockseek.config.Config;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The block that says what a run will do, against the settings it was given.
 *
 * <p>Three questions, which are the three ways this can lie: does a value reach the text, does
 * a missing key stay quiet instead of being guessed at, and is a key nobody taught it about
 * still printed. The first draft of the block was typed by hand and was wrong about one line
 * of ten within the hour, which is why it is generated and why these exist.
 */
class BriefTest {

    private static String say(Map<String, Number> numbers, Map<String, String> sockseek,
                              List<String> ladder) {
        return String.join("\n", Brief.lines(new LinkedHashMap<>(Map.of("mix", 10)),
                new Config(Map.of(), numbers, Map.of(), Map.of()), sockseek, ladder,
                Path.of("output"), Path.of("logs")));
    }

    @Test
    void theNumbersComeFromTheSettingsAndNotFromTheText() {
        assertTrue(say(Map.of("attempts_per_pass", 3), Map.of(), List.of())
                .contains("3 per track per pass"));
        assertTrue(say(Map.of("attempts_per_pass", 7), Map.of(), List.of())
                .contains("7 per track per pass"));
        String ladder = say(Map.of(), Map.of(), List.of("mp3@256", "flac"));
        assertTrue(ladder.contains("1  mp3 at 256+ kbps"), ladder);
        assertTrue(ladder.contains("2  flac"), ladder);
        // and nothing else: the pass that took anything at all undid every floor above it
        assertFalse(ladder.contains("any format"), ladder);
    }

    @Test
    void whatIsNotSetIsNotGuessedAt() {
        String bare = say(Map.of(), Map.of(), List.of());
        // sockseek's own default is 10 in its help today and may be anything tomorrow
        assertTrue(bare.contains("searches-per-time: not set, so sockseek decides it"), bare);
        assertFalse(bare.contains("source bans"), bare);        // empty sections do not print
        String off = say(new LinkedHashMap<>(Map.of("stall_timeout_s", 0, "enabled", 0)),
                Map.of(), List.of());
        assertTrue(off.contains("nothing is stopped for going quiet"), off);
        assertTrue(off.contains("off - every track is searched for again"), off);
    }

    @Test
    void nothingIsHiddenAndTheLoginIsNeverShown() {
        String said = say(Map.of(), new LinkedHashMap<>(Map.of(
                "some-new-knob", "17", "username", "someone", "password", "a-secret")),
                List.of());
        assertTrue(said.contains("some-new-knob = 17"), said);
        assertFalse(said.contains("a-secret"), said);
        assertFalse(said.contains("someone"), said);
    }
}
