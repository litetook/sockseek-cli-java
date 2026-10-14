package dev.litetook.sockseek;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The opening menu, which is all this phase of the port has.
 *
 * <p>The questions and the lines are injected rather than read from a console, exactly as the
 * Python tool does it: what is tested is the behaviour someone sees, not how it is printed.
 */
class AppTest {

    /** Scripted answers; when they run out, input has ended. */
    private static final class Script implements App.Ask {
        private final Deque<String> answers = new ArrayDeque<>();
        final List<String> printed = new ArrayList<>();

        Script(String... said) {
            answers.addAll(List.of(said));
        }

        @Override
        public Optional<String> ask(String prompt) {
            printed.add(prompt);
            return Optional.ofNullable(answers.poll());
        }
    }

    private static Script run(String... answers) {
        Script script = new Script(answers);
        App.run(new String[0], script, script.printed::add);
        return script;
    }

    @Test
    void theMenuOffersThreeWaysIn() {
        List<String> printed = run().printed;
        assertEquals(List.of(
                "Where from?",
                "",
                "  1  download a playlist from a .txt file",
                "  2  save a .txt from a Spotify link, then download it",
                "  3  save a .txt from a SoundCloud link, then download it",
                "",
                "Pick one (Enter quits): "), printed);
    }

    @Test
    void enterQuitsAndSoDoesTheEndOfInput() {
        assertEquals(Optional.empty(), App.sourceMenu(new Script(""), line -> { }));
        assertEquals(Optional.empty(), App.sourceMenu(new Script(), line -> { }));
    }

    @Test
    void aNumberPicksItsSource() {
        assertEquals(Optional.of(App.Source.TXT), App.sourceMenu(new Script("1"), line -> { }));
        assertEquals(Optional.of(App.Source.SPOTIFY),
                App.sourceMenu(new Script("2"), line -> { }));
        assertEquals(Optional.of(App.Source.SOUNDCLOUD),
                App.sourceMenu(new Script("3"), line -> { }));
    }

    @Test
    void aWrongAnswerAsksAgainInsteadOfGuessing() {
        Script script = run("7", "x", "");
        assertTrue(script.printed.contains("Type 1, 2 or 3, or Enter to quit."),
                script.printed.toString());
    }

    @Test
    void choosingSomethingSaysItIsNotBuiltYet() {
        // honest while the port is half done: no silent nothing-happens
        Script script = run("2");
        assertTrue(script.printed.stream().anyMatch(line -> line.contains("Not here yet")),
                script.printed.toString());
    }
}
