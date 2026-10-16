package dev.litetook.sockseek.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.litetook.sockseek.process.Events;
import java.nio.file.Path;
import java.time.LocalDate;
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

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 17);

    private static Map<String, Events.Event> said(Events.Event... events) {
        Map<String, Events.Event> byLine = new LinkedHashMap<>();
        int row = 1;
        for (Events.Event event : events) {
            byLine.put("line " + row++, event);
        }
        return byLine;
    }

    @Test
    void onlyATrackNobodyHadIsRemembered() {
        Map<String, Events.Event> byLine = new LinkedHashMap<>();
        byLine.put("A - nobody has this", new Events.Event(1, "Failed", "NoSearchResults"));
        byLine.put("B - found but not sent", new Events.Event(2, "Failed", "DownloadFailed"));
        byLine.put("C - arrived", new Events.Event(3, "Succeeded", "None"));
        List<Failures.Miss> after = Failures.after(List.of(), "mix", byLine, TODAY);
        assertEquals(List.of("A - nobody has this"), after.stream().map(Failures.Miss::line)
                .toList());
    }

    @Test
    void aTrackThatArrivesIsForgotten() {
        List<Failures.Miss> before = List.of(
                new Failures.Miss(TODAY.minusDays(1), "NoSearchResults", "mix", "A - one"));
        Map<String, Events.Event> byLine = new LinkedHashMap<>();
        byLine.put("A - one", new Events.Event(1, "Succeeded", "None"));
        assertEquals(List.of(), Failures.after(before, "mix", byLine, TODAY));
    }

    @Test
    void anotherPlaylistIsLeftAlone() {
        List<Failures.Miss> before = List.of(
                new Failures.Miss(TODAY, "NoSearchResults", "other", "A - one"));
        List<Failures.Miss> after = Failures.after(before, "mix",
                said(new Events.Event(1, "Succeeded", "None")), TODAY);
        assertEquals(before, after);
    }

    @Test
    void theWindowIsCountedInWholeDays() {
        List<Failures.Miss> all = List.of(
                new Failures.Miss(TODAY, "NoSearchResults", "mix", "today"),
                new Failures.Miss(TODAY.minusDays(1), "NoSearchResults", "mix", "yesterday"),
                new Failures.Miss(TODAY.minusDays(6), "NoSearchResults", "mix", "last week"));
        // one day means "not again today"; yesterday's miss is already worth another look,
        // because a track absent on the 6th arrived on the 7th as a 893 kbps flac
        assertEquals(Set.of("today"), Failures.skippable(all, "mix", 1, TODAY));
        assertEquals(Set.of("today", "yesterday"), Failures.skippable(all, "mix", 2, TODAY));
        assertEquals(Set.of("today", "yesterday", "last week"),
                Failures.skippable(all, "mix", 7, TODAY));
    }

    @Test
    void zeroDaysSearchesEverythingAgain() {
        List<Failures.Miss> all = List.of(
                new Failures.Miss(TODAY, "NoSearchResults", "mix", "today"));
        assertEquals(Set.of(), Failures.skippable(all, "mix", 0, TODAY));
    }

    @Test
    void aFoundButUndeliveredTrackIsNeverSkipped() {
        // these are the ones that most often arrive next time, so they are always searched
        List<Failures.Miss> all = List.of(
                new Failures.Miss(TODAY, "DownloadFailed", "mix", "A - one"));
        assertEquals(Set.of(), Failures.skippable(all, "mix", 7, TODAY));
    }

    @Test
    void itSurvivesTheRoundTripThroughTheFile(@TempDir Path home) {
        Path file = home.resolve(Failures.FILE_NAME);
        // a playlist line holds anything: commas, pipes, brackets, an em dash
        List<Failures.Miss> all = List.of(
                new Failures.Miss(TODAY, "NoSearchResults", "liked-techno-hard",
                        "AC/DC, Live - T.N.T. (Remix) [Free DL] | 4:09"));
        assertTrue(Failures.write(file, all));
        assertEquals(all, Failures.read(file));
    }

    @Test
    void aHandEditedLineIsSkippedRatherThanFatal(@TempDir Path home) {
        Path file = home.resolve(Failures.FILE_NAME);
        assertTrue(Failures.write(file, List.of(
                new Failures.Miss(TODAY, "NoSearchResults", "mix", "A - one"))));
        assertEquals(1, Failures.read(file).size());
        assertEquals(List.of(), Failures.read(home.resolve("not-there.tsv")));
    }
}
