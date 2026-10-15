package dev.litetook.sockseek.explain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.litetook.sockseek.index.Failure;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/** Why a track did not arrive. */
class ExplainTest {

    /** Copied from run #0005: real Soulseek, --print results, every condition off. */
    private static final String PREVIEW = """
            Results for Paradisio - Bailando (Arman John Edit) (604s):
            No results

            Results for You Got The Love (Club Calimari Remix) (319s):

            [346s/320kbps /13.2MB] DJ-Promo\\..\\The Source - You Got The Love (Club Calimari Remix).mp3
            [346s/320kbps /13.2MB] DJ-Promo\\..\\Club Calimari - You Got The Love (Club Calimari Remix) 140.mp3
            Total: 2


            Results for Justin Timberlake - Sexy Back (SUITSIDE Edit) (267s):

            [267s/320kbps /10.2MB] DJ-Promo\\..\\Justin Timberlake - Sexy Back (Suitside Edit).mp3
            Total: 1
            """;

    private static Failure failure(String reason) {
        return new Failure("You Got The Love (Club Calimari Remix) | 5:19",
                "You Got The Love (Club Calimari Remix)", "319", "2", reason);
    }

    private static String text(List<String> lines) {
        return String.join(" ", lines);
    }

    @Test
    void theParserGroupsCandidatesByTitleAndKeepsTheEmptyOnes() {
        Map<String, List<Candidate>> found = Explain.parsePreview(PREVIEW);
        assertEquals(3, found.size());
        // a title with no candidates maps to an empty list, which is the whole point
        assertEquals(List.of(), found.get("Paradisio - Bailando (Arman John Edit)"));
        List<Candidate> two = found.get("You Got The Love (Club Calimari Remix)");
        assertEquals(List.of(346, 346), two.stream().map(c -> c.length().getAsInt()).toList());
        assertEquals(List.of(320, 320), two.stream().map(c -> c.bitrate().getAsInt()).toList());
        assertTrue(two.get(1).path().contains("Club Calimari"));
    }

    @Test
    void anUnknownBitrateDoesNotBreakTheParser() {
        Map<String, List<Candidate>> found = Explain.parsePreview(
                "Results for X (10s):\n\n[12s/?kbps /1.0MB] p\\q.mp3\nTotal: 1\n");
        assertEquals(12, found.get("X").get(0).length().getAsInt());
        assertEquals(OptionalInt.empty(), found.get("X").get(0).bitrate());
    }

    @Test
    void anEmptySearchAfterAReasonFourFailureIsNotCalledAnAbsence() {
        // the index already said the run found and tried a candidate, so an empty extra search
        // is a search that did not answer, not proof that nothing is shared. Measured twice on
        // 2026-10-11 (runs #0028 and #0029).
        String said = text(Explain.explainFailure(failure("4"), Optional.of(List.of()), 45,
                OptionalInt.of(256)));
        assertTrue(said.contains("did not answer in time"), said);
        assertFalse(said.contains("nothing on the network"), said);
    }

    @Test
    void nothingOnTheNetworkIsSaidPlainly() {
        List<String> lines = Explain.explainFailure(failure("9"), Optional.of(List.of()), 3,
                OptionalInt.of(256));
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("nothing on the network"), lines.get(0));
    }

    @Test
    void rejectedByLengthNamesTheGapAndTheFlagThatWouldTakeIt() {
        List<String> lines = Explain.explainFailure(failure("9"),
                Optional.of(Explain.parsePreview(PREVIEW)
                        .get("You Got The Love (Club Calimari Remix)")), 3, OptionalInt.of(256));
        String said = text(lines);
        assertTrue(said.contains("IS on the network"), said);
        assertTrue(said.contains("346s/320kbps"), said);
        assertTrue(said.contains("diff +27s"), said);
        assertTrue(said.contains("--length-tol 28"), said);   // 27 + 1, so the file fits
    }

    @Test
    void rejectedByQualityNamesTheFloorAndWhereItComesFrom() {
        String said = text(Explain.explainFailure(failure("9"),
                Optional.of(List.of(new Candidate(OptionalInt.of(319), OptionalInt.of(65),
                        "p/q.opus"))), 3, OptionalInt.of(256)));
        assertTrue(said.contains("rejected by quality"), said);
        assertTrue(said.contains("65kbps"), said);
        assertTrue(said.contains("sockseek.conf"), said);
    }

    @Test
    void aFineCandidateMeansTheUploaderWentSilent() {
        String said = text(Explain.explainFailure(failure("9"),
                Optional.of(List.of(new Candidate(OptionalInt.of(319), OptionalInt.of(320),
                        "p/q.mp3"))), 3, OptionalInt.of(256)));
        assertTrue(said.contains("uploader did not deliver"), said);
    }

    @Test
    void notSearchedIsNotReportedAsAbsent() {
        // --no-explain must never turn into "nothing on the network": a different claim
        List<String> lines = Explain.explainFailure(failure("9"), Optional.empty(), 3,
                OptionalInt.of(256));
        assertTrue(lines.get(0).contains("not checked"), lines.get(0));
        assertFalse(text(lines).contains("nothing on the network"), text(lines));
    }

    @Test
    void aCandidateWithoutALengthIsNotJudgedByLength() {
        String said = text(Explain.explainFailure(failure("9"),
                Optional.of(List.of(new Candidate(OptionalInt.empty(), OptionalInt.of(320),
                        "p/q.mp3"))), 3, OptionalInt.of(256)));
        assertTrue(said.contains("IS on the network"), said);
        assertFalse(said.contains("rejected by length"), said);
    }
}
