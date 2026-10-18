package dev.litetook.sockseek.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.litetook.sockseek.process.Events;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The cache of tracks nobody had.
 *
 * <p>What is tested is the promise behind it: a search is only ever skipped for the one reason
 * that stays true for a while - nobody shared the file - and only for as long as the owner
 * said. Everything else about Soulseek changes by the hour.
 */
class FailuresTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 17, 12, 0);

    private static Map<String, Events.Event> said(Events.Event... events) {
        Map<String, Events.Event> byLine = new LinkedHashMap<>();
        int row = 1;
        for (Events.Event event : events) {
            byLine.put("line " + row++, event);
        }
        return byLine;
    }

    @Test
    void everyFailureIsRemembered_butOnlyOneClassIsEverSkipped() {
        Map<String, Events.Event> byLine = new LinkedHashMap<>();
        byLine.put("A - nobody has this", new Events.Event(1, "Failed", "NoSearchResults"));
        byLine.put("B - upload died", new Events.Event(2, "Failed", "AllDownloadsFailed"));
        byLine.put("C - arrived", new Events.Event(3, "Succeeded", "None"));
        byLine.put("D - was already here", new Events.Event(4, "AlreadyExists", "None"));
        List<Failures.Outcome> after = Failures.after(List.of(), "mix", byLine, NOW);
        // both failures are written down; what arrived is forgotten, the folder knows better
        assertEquals(List.of("A - nobody has this", "B - upload died"),
                after.stream().map(Failures.Outcome::line).toList());
        // and NOTHING is held back: the plain call knows neither what the run asked for
        // nor under what conditions, and sockseek's own word for "no results" also covers
        // "found it and your filters refused it"
        assertEquals(Set.of(), Failures.skippable(after, "mix", 1, NOW, ""));
        // the run says which lines it asked for with no format required and nothing able to
        // refuse a file; THAT is what turns "no results" into "nobody has it"
        List<Failures.Outcome> checked = Failures.after(List.of(), "mix", byLine, NOW,
                Set.of("A - nobody has this"), "");
        assertEquals(Set.of("A - nobody has this"),
                Failures.skippable(checked, "mix", 1, NOW, ""));
    }

    @Test
    void aHardConditionMakesTheRunSayLessThanItWouldHaveClaimed() {
        // with a filter able to refuse a file, "nothing came back" cannot mean "nobody has
        // it" - and the row says so, carries the conditions, and is never skipped
        Map<String, Events.Event> byLine = new LinkedHashMap<>();
        byLine.put("A - one", new Events.Event(1, "Failed", "NoSearchResults"));
        List<Failures.Outcome> after = Failures.after(List.of(), "mix", byLine, NOW,
                Set.of("A - one"), "len=45,min-bitrate=256");
        assertEquals(Failures.REFUSED_OR_ABSENT, after.get(0).reason());
        assertEquals("len=45,min-bitrate=256", after.get(0).conditions());
        assertEquals(Set.of(), Failures.skippable(after, "mix", 1, NOW,
                "len=45,min-bitrate=256"));
    }

    @Test
    void aRowRememberedUnderOtherConditionsIsNotActedOn() {
        // turning a filter off has to take effect NOW, not when the window runs out: what
        // was asked under other conditions answers a different question
        List<Failures.Outcome> all = List.of(new Failures.Outcome(NOW,
                Failures.CONFIRMED_ABSENT, 1, "len=45", "mix", "A - one"));
        assertEquals(Set.of("A - one"), Failures.skippable(all, "mix", 2, NOW, "len=45"));
        assertEquals(Set.of(), Failures.skippable(all, "mix", 2, NOW, ""));
        assertEquals(Set.of(), Failures.skippable(all, "mix", 2, NOW, "len=10"));
    }

    @Test
    void aLineTheRunNeverAskedWideOpenAboutKeepsSockseekWord() {
        // a pass cut short: the tool knows nothing it can act on, and says exactly that
        Map<String, Events.Event> byLine = new LinkedHashMap<>();
        byLine.put("A - one", new Events.Event(1, "Failed", "NoSearchResults"));
        List<Failures.Outcome> after = Failures.after(List.of(), "mix", byLine, NOW,
                Set.of(), "");
        assertEquals("NoSearchResults", after.get(0).reason());
        assertEquals(Set.of(), Failures.skippable(after, "mix", 1, NOW, ""));
    }

    @Test
    void anUploadThatDiedIsNeverReinterpreted() {
        Map<String, Events.Event> byLine = new LinkedHashMap<>();
        byLine.put("A - one", new Events.Event(1, "Failed", "AllDownloadsFailed"));
        assertEquals("AllDownloadsFailed", Failures.after(List.of(), "mix", byLine, NOW,
                Set.of("A - one"), "").get(0).reason());
    }

    @Test
    void theConditionsStringIsSpelledOnlyOneWay() {
        assertEquals("", Failures.conditions(-1, List.of()));
        assertEquals("len=45", Failures.conditions(45, List.of()));
        assertEquals("min-bitrate=256", Failures.conditions(-1, List.of("min-bitrate=256")));
        assertEquals("len=0,min-bitrate=256,format=mp3",
                Failures.conditions(0, List.of("min-bitrate=256", "format=mp3")));
    }

    @Test
    void theOldFiveFieldRowsAreStillRead(@TempDir Path home) throws Exception {
        // the file had no conditions column before 2026-10-18; those rows read as "none",
        // which is safe - they are all in classes the cache never skips anyway
        Path file = home.resolve(Failures.FILE_NAME);
        java.nio.file.Files.writeString(file, String.join("\t",
                "2026-10-17T12:24:31", "AllDownloadsFailed", "4", "mix", "A - one") + "\n");
        List<Failures.Outcome> read = Failures.read(file);
        assertEquals(1, read.size());
        assertEquals("", read.get(0).conditions());
        assertEquals("mix", read.get(0).playlist());
        assertEquals("A - one", read.get(0).line());
    }

    @Test
    void theSameFailureTwiceRunningCountsUp() {
        Map<String, Events.Event> byLine = new LinkedHashMap<>();
        byLine.put("A - one", new Events.Event(1, "Failed", "AllDownloadsFailed"));
        List<Failures.Outcome> first = Failures.after(List.of(), "mix", byLine, NOW);
        assertEquals(1, first.get(0).runs());
        List<Failures.Outcome> second = Failures.after(first, "mix", byLine, NOW);
        assertEquals(2, second.get(0).runs());
        // a different cause is a different story, so the count starts again
        byLine.put("A - one", new Events.Event(1, "Failed", "NoSearchResults"));
        assertEquals(1, Failures.after(second, "mix", byLine, NOW).get(0).runs());
    }

    @Test
    void theOnesFailingTheSameWayForThreeRunsAreNamed() {
        List<Failures.Outcome> all = List.of(
                new Failures.Outcome(NOW, "AllDownloadsFailed", 3, "", "mix", "A - stuck"),
                new Failures.Outcome(NOW, "AllDownloadsFailed", 2, "", "mix", "B - not yet"),
                new Failures.Outcome(NOW, "AllDownloadsFailed", 9, "", "other", "C - elsewhere"));
        assertEquals(List.of("A - stuck"),
                Failures.stuck(all, "mix").stream().map(Failures.Outcome::line).toList());
    }

    @Test
    void aTrackThatArrivesIsForgotten() {
        List<Failures.Outcome> before = List.of(
                new Failures.Outcome(NOW.minusHours(24 * 1), "NoSearchResults", 1, "", "mix", "A - one"));
        Map<String, Events.Event> byLine = new LinkedHashMap<>();
        byLine.put("A - one", new Events.Event(1, "Succeeded", "None"));
        assertEquals(List.of(), Failures.after(before, "mix", byLine, NOW));
    }

    @Test
    void anotherPlaylistIsLeftAlone() {
        List<Failures.Outcome> before = List.of(
                new Failures.Outcome(NOW, "NoSearchResults", 1, "", "other", "A - one"));
        List<Failures.Outcome> after = Failures.after(before, "mix",
                said(new Events.Event(1, "Succeeded", "None")), NOW);
        assertEquals(before, after);
    }

    @Test
    void theWindowIsCountedInHours() {
        // hours, not days, since the owner saw tracks appear when he ran at another time of
        // the same day: "деякі треки просто з'явились у пошуку коли я в інший час запустив"
        List<Failures.Outcome> all = List.of(
                new Failures.Outcome(NOW.minusMinutes(20), Failures.CONFIRMED_ABSENT, 1, "", "mix", "just now"),
                new Failures.Outcome(NOW.minusHours(3), Failures.CONFIRMED_ABSENT, 1, "", "mix", "this morning"),
                new Failures.Outcome(NOW.minusHours(30), Failures.CONFIRMED_ABSENT, 1, "", "mix", "yesterday"));
        assertEquals(Set.of("just now"), Failures.skippable(all, "mix", 2, NOW, ""));
        assertEquals(Set.of("just now", "this morning"), Failures.skippable(all, "mix", 4, NOW, ""));
        assertEquals(Set.of("just now", "this morning", "yesterday"),
                Failures.skippable(all, "mix", 48, NOW, ""));
        // and a fraction is a real answer, not a rounding error
        assertEquals(Set.of(), Failures.skippable(all, "mix", 0.25, NOW, ""));
        assertEquals(Set.of("just now"), Failures.skippable(all, "mix", 0.5, NOW, ""));
    }

    @Test
    void zeroHoursSearchesEverythingAgain() {
        List<Failures.Outcome> all = List.of(
                new Failures.Outcome(NOW, Failures.CONFIRMED_ABSENT, 1, "", "mix", "just now"));
        assertEquals(Set.of(), Failures.skippable(all, "mix", 0, NOW, ""));
    }

    @Test
    void theDateOnlyFormOfTheOldFileIsStillRead(@TempDir Path home) throws Exception {
        // a day of real records already existed when the unit changed; a date with no time
        // counts as that day's midnight, which can only make a row look older, never younger
        Path file = home.resolve(Failures.FILE_NAME);
        java.nio.file.Files.writeString(file, String.join("\t",
                "2026-10-17", "NoSearchResults", "3", "", "mix", "Artist - Title | 4:09")
                + "\n");
        List<Failures.Outcome> read = Failures.read(file);
        assertEquals(1, read.size());
        assertEquals(LocalDateTime.of(2026, 10, 17, 0, 0), read.get(0).when());
        assertEquals(3, read.get(0).runs());
    }

    @Test
    void aFoundButUndeliveredTrackIsNeverSkipped() {
        // these are the ones that most often arrive next time, so they are always searched
        List<Failures.Outcome> all = List.of(
                new Failures.Outcome(NOW, "DownloadFailed", 1, "", "mix", "A - one"));
        assertEquals(Set.of(), Failures.skippable(all, "mix", 7, NOW, ""));
    }

    @Test
    void itSurvivesTheRoundTripThroughTheFile(@TempDir Path home) {
        Path file = home.resolve(Failures.FILE_NAME);
        // a playlist line holds anything: commas, pipes, brackets, an em dash
        List<Failures.Outcome> all = List.of(
                new Failures.Outcome(NOW, "NoSearchResults", 1, "", "techno-hard",
                        "AC/DC, Live - T.N.T. (Remix) [Free DL] | 4:09"));
        assertTrue(Failures.write(file, all));
        assertEquals(all, Failures.read(file));
    }

    @Test
    void aHandEditedLineIsSkippedRatherThanFatal(@TempDir Path home) {
        Path file = home.resolve(Failures.FILE_NAME);
        assertTrue(Failures.write(file, List.of(
                new Failures.Outcome(NOW, "NoSearchResults", 1, "", "mix", "A - one"))));
        assertEquals(1, Failures.read(file).size());
        assertEquals(List.of(), Failures.read(home.resolve("not-there.tsv")));
    }
}
