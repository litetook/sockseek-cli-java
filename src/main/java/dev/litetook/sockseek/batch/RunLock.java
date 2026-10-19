package dev.litetook.sockseek.batch;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/**
 * One download at a time in one folder.
 *
 * <p>Two runs over the same folder are not twice as fast: they search on one account against
 * one rate limit, they write the same {@code _index.csv} and the same cache, and the second one
 * decides what is missing from a folder the first one is still filling. Until the web page
 * arrived there was only ever one way in and the question did not come up; now the console and
 * the page can both start a run, so the question has to be answered somewhere.
 *
 * <p>It is an OS file lock rather than a flag file, for one reason: the operating system drops
 * it when the process dies. A flag file left behind by a crash would lock the owner out of his
 * own tool until he found the file and deleted it, and he would be right to be annoyed.
 *
 * <p>A folder that cannot be locked at all - no permission, a network share that does not
 * support it - is not a reason to refuse to download. Then {@link #taken()} says the guard is
 * off and the run goes ahead, because a missing guard against a rare mistake is better than a
 * tool that will not start.
 */
public final class RunLock implements AutoCloseable {

    /** The file both ways in agree on. It holds nothing; only the lock on it matters. */
    public static final String NAME = "run.lock";

    private final FileChannel channel;
    private final FileLock lock;

    private RunLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    /**
     * The lock, or empty when another run already holds it.
     *
     * <p>A lock that could not be attempted at all comes back as a held lock with nothing
     * underneath it: the caller goes on, and nothing in the tool has to know the difference.
     */
    public static Optional<RunLock> tryAcquire(Path workDir) {
        if (workDir == null) {
            return Optional.of(new RunLock(null, null));
        }
        FileChannel channel = null;
        try {
            Files.createDirectories(workDir);
            channel = FileChannel.open(workDir.resolve(NAME), StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE);
            FileLock held = channel.tryLock();
            if (held == null) {
                channel.close();
                return Optional.empty();
            }
            return Optional.of(new RunLock(channel, held));
        } catch (OverlappingFileLockException alreadyMine) {
            // another thread of THIS process holds it - the page starting a second run while
            // the first is going. The answer is the same: there is a run already
            close(channel);
            return Optional.empty();
        } catch (IOException cannotLock) {
            close(channel);
            return Optional.of(new RunLock(null, null));
        }
    }

    /** Whether a real lock is held, as opposed to a folder that cannot be locked. */
    public boolean taken() {
        return lock != null;
    }

    @Override
    public void close() {
        try {
            if (lock != null) {
                lock.release();
            }
        } catch (IOException ignored) {
            // releasing a lock the OS is about to drop anyway
        }
        close(channel);
    }

    private static void close(FileChannel channel) {
        try {
            if (channel != null) {
                channel.close();
            }
        } catch (IOException ignored) {
            // nothing useful can be done about a channel that will not close
        }
    }
}
