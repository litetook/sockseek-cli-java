package dev.litetook.sockseek.index;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;

/**
 * Whether a file that has just arrived is better than the one already on disk.
 *
 * <p>Judged by the same thing the quality ladder is sorted by - the bitrate - and worked out
 * without reading a single byte of audio, which is a decision of 2026-10-16 that stands. The
 * playlist already says how long the track is, and the file says how big it is, so
 * {@code bytes * 8 / seconds} is the bitrate to within a few percent. That is the "quality
 * attribute at hand" the owner asked for on 2026-10-19, and it is why a run can tell that an
 * mp3 it took at 128 kbps last week is beatable today.
 *
 * <p>Without a length in the playlist there is nothing to divide by, and then the comparison
 * falls back to what little is left: lossless over lossy, or the same format at a bigger size.
 *
 * <p>Every rule leans the same way: when it cannot tell, it keeps what is already on disk.
 */
public final class Better {

    /** Audio that keeps everything. An upgrade to one of these is always an upgrade. */
    public static final List<String> LOSSLESS = List.of("flac", "wav", "alac", "aiff", "ape");

    /** How much better the newcomer has to be to be worth replacing anything: 10%. */
    public static final double MARGIN = 1.10;

    /**
     * Above this, an estimate is not a bitrate - it is a longer recording.
     *
     * <p>The length comes from the PLAYLIST, so a ten-minute DJ set standing in for a
     * four-minute track divides by the wrong number and comes out as a magnificent bitrate.
     * 2800 kbps is above anything real at CD quality (1411) and above the heaviest file the
     * owner has ever been sent (2722), so what it excludes is arithmetic, not music.
     */
    public static final int NOT_A_BITRATE = 2800;

    /** With no length to divide by: at least 5% bigger, and no more than 1.6x. */
    public static final double SIZE_MARGIN = 1.05;
    public static final double SIZE_TOO_MUCH = 1.6;

    private Better() {
    }

    /**
     * True when {@code arrived} should replace {@code existing}.
     *
     * @param seconds how long the playlist says the track is; empty when it does not say
     */
    public static boolean worthReplacing(Path arrived, Path existing, OptionalInt seconds) {
        String newKind = extension(arrived);
        String oldKind = extension(existing);
        if (newKind.isEmpty() || oldKind.isEmpty()) {
            return false;
        }
        boolean newLossless = LOSSLESS.contains(newKind);
        boolean oldLossless = LOSSLESS.contains(oldKind);
        if (newLossless != oldLossless) {
            return newLossless;   // lossless replaces lossy; lossy never replaces lossless
        }
        OptionalInt newRate = kbps(arrived, seconds);
        OptionalInt oldRate = kbps(existing, seconds);
        if (newRate.isPresent() && oldRate.isPresent()) {
            // the ladder sorts by bitrate, so this is the ladder's own answer
            return newRate.getAsInt() >= oldRate.getAsInt() * MARGIN;
        }
        if (!newKind.equals(oldKind)) {
            return false;   // two formats of the same kind and nothing to measure them by
        }
        long newSize = sizeOf(arrived);
        long oldSize = sizeOf(existing);
        if (newSize <= 0 || oldSize <= 0) {
            return false;
        }
        double ratio = (double) newSize / oldSize;
        return ratio >= SIZE_MARGIN && ratio <= SIZE_TOO_MUCH;
    }

    /**
     * The bitrate of that file for that track, in kbps, or empty when it cannot be had.
     *
     * <p>Empty when the playlist gave no length, when the file is not there, and when the
     * answer comes out above {@link #NOT_A_BITRATE} - which means the file is not the length
     * the playlist claims, so nothing here can compare it with anything.
     */
    public static OptionalInt kbps(Path file, OptionalInt seconds) {
        long size = sizeOf(file);
        if (size <= 0 || seconds.isEmpty() || seconds.getAsInt() <= 0) {
            return OptionalInt.empty();
        }
        int rate = (int) Math.round(size * 8.0 / seconds.getAsInt() / 1000.0);
        return rate <= 0 || rate > NOT_A_BITRATE ? OptionalInt.empty() : OptionalInt.of(rate);
    }

    /** Why it was or was not replaced, for the run log - one short line, no guessing. */
    public static String say(Path arrived, Path existing, OptionalInt seconds) {
        String newKind = extension(arrived);
        String oldKind = extension(existing);
        OptionalInt newRate = kbps(arrived, seconds);
        OptionalInt oldRate = kbps(existing, seconds);
        String rates = newRate.isPresent() && oldRate.isPresent()
                ? oldRate.getAsInt() + " -> " + newRate.getAsInt() + " kbps"
                : kb(sizeOf(existing)) + " -> " + kb(sizeOf(arrived));
        if (worthReplacing(arrived, existing, seconds)) {
            return LOSSLESS.contains(newKind) && !LOSSLESS.contains(oldKind)
                    ? newKind + " replaces " + oldKind + " (" + rates + ")"
                    : "better " + newKind + ": " + rates;
        }
        if (LOSSLESS.contains(oldKind) && !LOSSLESS.contains(newKind)) {
            return "kept your " + oldKind + "; " + newKind + " is not an upgrade of it";
        }
        if (newRate.isEmpty() && oldRate.isPresent()) {
            return "kept yours: the new one is not the length the playlist claims";
        }
        if (!newKind.equals(oldKind)) {
            return "kept yours: " + newKind + " and " + oldKind + " cannot be compared";
        }
        return "kept yours: " + rates + " is no better";
    }

    private static String kb(long bytes) {
        return bytes <= 0 ? "?" : (bytes / 1024) + " KB";
    }

    private static long sizeOf(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.size(file) : -1;
        } catch (IOException cannotTell) {
            return -1;
        }
    }

    private static String extension(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot <= 0 || dot == name.length() - 1 ? "" : name.substring(dot + 1);
    }
}
