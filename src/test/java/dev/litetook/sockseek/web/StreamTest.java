package dev.litetook.sockseek.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** The live log's wire format, and what a page that arrives late is told. */
class StreamTest {

    private static String seen(ByteArrayOutputStream watcher) {
        return watcher.toString(StandardCharsets.UTF_8);
    }

    @Test
    void aLineReachesEveryPageWatching() throws IOException {
        Stream stream = new Stream();
        ByteArrayOutputStream one = new ByteArrayOutputStream();
        ByteArrayOutputStream two = new ByteArrayOutputStream();
        stream.join(one);
        stream.join(two);
        stream.log(">> 1 of 2 done, 1 downloaded");
        assertEquals("event: log\ndata: >> 1 of 2 done, 1 downloaded\n\n", seen(one));
        assertEquals(seen(one), seen(two));
    }

    @Test
    void aPageThatArrivesLateIsGivenWhatItMissed() throws IOException {
        Stream stream = new Stream();
        stream.log("pass 1 of 4: mp3 only at 256+ kbps, 18 track(s)");
        stream.log("OK  Artist - Title | 4:00");
        ByteArrayOutputStream late = new ByteArrayOutputStream();
        stream.join(late);
        assertTrue(seen(late).contains("pass 1 of 4"), seen(late));
        assertTrue(seen(late).contains("OK  Artist - Title"), seen(late));
    }

    @Test
    void aLineWithABreakInItStaysOneEvent() throws IOException {
        // a newline inside the data would end the event early and the page would lose the rest
        Stream stream = new Stream();
        ByteArrayOutputStream watcher = new ByteArrayOutputStream();
        stream.join(watcher);
        stream.log("first\nsecond");
        assertEquals("event: log\ndata: first\ndata: second\n\n", seen(watcher));
    }

    @Test
    void theStateIsNotKeptForLaterBecauseItGoesStale() throws IOException {
        Stream stream = new Stream();
        stream.state("{\"running\":true}");
        ByteArrayOutputStream late = new ByteArrayOutputStream();
        stream.join(late);
        assertEquals("", seen(late));
    }

    @Test
    void aPageThatWentAwayIsDroppedRatherThanBreakingTheRun() throws IOException {
        Stream stream = new Stream();
        ByteArrayOutputStream gone = new ByteArrayOutputStream() {
            @Override
            public void flush() throws IOException {
                throw new IOException("the page was closed");
            }
        };
        ByteArrayOutputStream still = new ByteArrayOutputStream();
        stream.join(still);
        try {
            stream.join(gone);
        } catch (IOException expected) {
            // join flushes the history, so a page that is already gone fails here
        }
        stream.log("still going");
        assertTrue(seen(still).contains("still going"), seen(still));
    }
}
