package dev.litetook.sockseek.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Reading sockseek's own log, which is the only place two facts exist.
 *
 * <p>The login lines were measured on 2026-10-19, in a sandbox, against an account that
 * exists nowhere. The transfer lines are sockseek's own wordings, read out of the binary the
 * same evening - no real download was watched, so those two patterns are the part of this
 * that a live run still has to confirm.
 */
class TraceTest {

    @Test
    void theLoginIsReadBothWaysRound() {
        Trace.Said in = Trace.parse(
                "2026-10-19 21:44:19 [debug] [soulseek] Logged in as someone").orElseThrow();
        assertEquals(Trace.Login.IN, in.login());
        assertEquals("someone", in.about());

        Trace.Said out = Trace.parse(
                "2026-10-19 21:44:19 [error] [soulseek] Soulseek login failed: bad password")
                .orElseThrow();
        assertEquals(Trace.Login.OUT, out.login());
        assertTrue(out.about().contains("login failed"), out.about());
    }

    @Test
    void beingThrownOffCountsAsNotBeingSignedIn() {
        // the one that explains a run that suddenly went dead
        Trace.Said said = Trace.parse("[warn] [soulseek] Soulseek server kicked this client, "
                + "probably because the same account logged in elsewhere").orElseThrow();
        assertEquals(Trace.Login.OUT, said.login());
    }

    @Test
    void theUploaderOfAFailedTransferIsRemembered() {
        Trace.Tail tail = new Trace.Tail(null, said -> { });
        // the log names the uploader when the transfer starts and nowhere else
        assertEquals("Chouchoune75", Trace.parse(
                "[info] [jobs] Downloading: 'Artist - Title.mp3' from 'Chouchoune75'")
                .orElseThrow().uploader());
        assertTrue(Trace.parse("[error] [jobs] Error while downloading 'x' (attempt 2): "
                + "the connection was closed").orElseThrow().transferFailed());
        assertTrue(tail.wentBad().isEmpty());
    }

    @Test
    void anOrdinaryLineSaysNothing() {
        assertTrue(Trace.parse("[debug] [jobs] Building music directory index...").isEmpty());
        assertTrue(Trace.parse("").isEmpty());
        assertTrue(Trace.parse(null).isEmpty());
    }

    @Test
    void whatIsNotAboutTheLoginLeavesItAlone() {
        Map<String, Integer> none = new Trace.Tail(null, said -> { }).wentBad();
        assertTrue(none.isEmpty());
        assertEquals(Trace.Login.UNKNOWN, Trace.parse("Downloading: 'a' from 'b'")
                .orElseThrow().login());
    }
}
