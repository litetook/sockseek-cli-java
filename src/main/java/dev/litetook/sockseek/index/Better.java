package dev.litetook.sockseek.index;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Whether a file that has just arrived is better than the one already on disk.
 *
 * <p>The hard part is that this tool does not read audio - a decision of 2026-10-16 that
 * stands - so "better" has to be decided from the two things visible from outside: the
 * extension and the size. Both rules below are chosen so that being wrong means keeping what
 * you already had, never replacing something good with something worse:
 *
 * <ol>
 *   <li><b>Lossless beats lossy.</b> A flac or a wav is better than any mp3 of the same track,
 *       whatever the numbers say.
 *   <li><b>The same extension: the bigger file wins</b>, by at least {@link #MARGIN}, and by no
 *       more than {@link #TOO_MUCH}. Same track, same format, so more bytes mean a higher
 *       bitrate - and the ceiling is there because a file twice the size is usually a
 *       different recording: a longer edit, a DJ set with your track inside it. The length
 *       filter would catch that, but it is off in the owner's config, so the size has to.
 *   <li><b>Anything else keeps what is on disk.</b> Two different lossy formats, a file that
 *       cannot be measured, an upgrade that is only marginally bigger: not worth a replace.
 * </ol>
 *
 * <p>⚠ Not verified against a real upgrade run: there has not been one. What IS certain is the
 * direction of the risk - the rules refuse rather than replace whenever they cannot tell.
 */
public final class Better {

    /** Audio that keeps everything. An upgrade to one of these is always an upgrade. */
    public static final List<String> LOSSLESS = List.of("flac", "wav", "alac", "aiff", "ape");

    /** How much bigger the newcomer has to be before it is worth replacing anything: 5%. */
    public static final double MARGIN = 1.05;

    /** And how much bigger is too much to be the same recording: 1.6x. */
    public static final double TOO_MUCH = 1.6;

    private Better() {
    }

    /** True when {@code arrived} should replace {@code existing}. */
    public static boolean worthReplacing(Path arrived, Path existing) {
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
        if (!newKind.equals(oldKind)) {
            return false;   // two different formats of the same kind: no honest comparison
        }
        long newSize = sizeOf(arrived);
        long oldSize = sizeOf(existing);
        if (newSize <= 0 || oldSize <= 0) {
            return false;
        }
        double ratio = (double) newSize / oldSize;
        return ratio >= MARGIN && ratio <= TOO_MUCH;
    }

    /** Why it was or was not replaced, for the run log - one short line, no guessing. */
    public static String say(Path arrived, Path existing) {
        String newKind = extension(arrived);
        String oldKind = extension(existing);
        if (worthReplacing(arrived, existing)) {
            return LOSSLESS.contains(newKind) && !LOSSLESS.contains(oldKind)
                    ? newKind + " replaces " + oldKind
                    : "bigger " + newKind + ": " + kb(sizeOf(existing)) + " -> "
                            + kb(sizeOf(arrived));
        }
        if (!newKind.equals(oldKind)) {
            return "kept the " + oldKind + ", " + newKind + " is not an upgrade of it";
        }
        long newSize = sizeOf(arrived);
        long oldSize = sizeOf(existing);
        if (newSize > oldSize * TOO_MUCH) {
            return "kept yours: the new one is " + kb(newSize) + " against " + kb(oldSize)
                    + ", too much to be the same recording";
        }
        return "kept yours: " + kb(newSize) + " is no better than " + kb(oldSize);
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
