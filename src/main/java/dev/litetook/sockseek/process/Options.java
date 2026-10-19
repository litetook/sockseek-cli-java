package dev.litetook.sockseek.process;

/**
 * What the tool itself puts on the sockseek command line.
 *
 * <p>Everything else - the quality floor, the format and bitrate preferences, the waiting
 * windows - is sockseek's own and lives in sockseek.conf. A value here would silently beat that
 * file, which is how pref-min-bitrate in the conf was dead until 2026-10-11.
 *
 * <p>{@code attemptsPerPass} is the exception that proves the rule, and it is here because
 * every name it has had so far was lying. sockseek calls it {@code max-retries}, which sounds
 * like trying the same uploader again; we called it {@code max_sources}, which sounds like a
 * count of people. What it counts is download attempts for ONE track inside ONE pass, and each
 * attempt is the next candidate that fits that pass. Owning it means one honest name in one
 * place, instead of a misleading one in the conf that quietly beats anything else.
 *
 * @param lengthTol seconds around the playlist length; -1 turns the filter off
 * @param preview ask sockseek to print the search results instead of downloading
 * <p>{@code format} is the one hard format of a single pass, and it is null for most of
 * them. sockseek's {@code pref-format} is unordered by its own documentation - "flac,mp3
 * does not prioritize flac over mp3" - so the only way to say "mp3 first, then flac, then
 * whatever else" is to ask for them one at a time, with the hard filter, over whatever is
 * still missing. See {@code format_order}.
 *
 * @param attemptsPerPass download tries for one track inside one pass
 * @param format the single format this pass will accept, or null for any
 * @param minBitrate kbps this pass refuses anything below, or -1 for no floor. Per pass and
 *                   not in the conf on purpose: the point of a ladder is "mp3, but only a
 *                   decent one; flac at any bitrate; and then anything at all", which one
 *                   global floor cannot say. The last pass never carries one, so a floor
 *                   here costs no coverage - it only decides what is taken FIRST
 */
public record Options(int lengthTol, boolean preview, int attemptsPerPass, String format,
                      int minBitrate) {

    public static Options defaults() {
        return new Options(3, false, 3, null, -1);
    }

    /** The same options, asking for one format only; null means any, -1 no floor. */
    public Options withPass(String only, int floor) {
        return new Options(lengthTol, preview, attemptsPerPass, only, floor);
    }

    public Options withLengthTol(int seconds) {
        return new Options(seconds, preview, attemptsPerPass, format, minBitrate);
    }
}
