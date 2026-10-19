package dev.litetook.sockseek.batch;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** One download at a time in one folder - the console and the web page are two ways in. */
class RunLockTest {

    @TempDir
    Path work;

    @Test
    void theSecondRunIsTurnedAway() {
        try (RunLock first = RunLock.tryAcquire(work).orElseThrow()) {
            assertTrue(first.taken());
            assertTrue(RunLock.tryAcquire(work).isEmpty(), "two runs got in");
        }
    }

    @Test
    void theLockIsFreeAgainAfterwards() {
        RunLock.tryAcquire(work).orElseThrow().close();
        Optional<RunLock> again = RunLock.tryAcquire(work);
        assertTrue(again.isPresent(), "the lock was not released");
        again.get().close();
    }

    @Test
    void aFolderThatIsNotThereYetIsMadeAndLocked() {
        Path fresh = work.resolve("work-that-does-not-exist-yet");
        try (RunLock lock = RunLock.tryAcquire(fresh).orElseThrow()) {
            assertTrue(lock.taken());
            assertTrue(Files.isRegularFile(fresh.resolve(RunLock.NAME)));
        }
    }

    @Test
    void withNowhereToLockTheRunStillGoesAhead() {
        // a guard against a rare mistake must not be a reason the tool will not start
        RunLock lock = RunLock.tryAcquire(null).orElseThrow();
        assertFalse(lock.taken());
        lock.close();
    }
}
