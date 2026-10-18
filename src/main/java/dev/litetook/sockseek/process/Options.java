package dev.litetook.sockseek.process;

/**
 * What the tool itself puts on the sockseek command line.
 *
 * <p>Everything else - the quality floor, the format and bitrate preferences, the waiting
 * windows - is sockseek's own and lives in sockseek.conf. A value here would silently beat that
 * file, which is how pref-min-bitrate in the conf was dead until 2026-10-11.
 *
 * <p>{@code maxSources} is the exception that proves the rule, and it is here because its
 * name was lying. sockseek calls it {@code max-retries}, which sounds like trying the same
 * uploader again; what it really counts is how many DIFFERENT uploaders a track is offered
 * to before it is given up on. Owning it means one honest name in one place, instead of a
 * misleading one in the conf that quietly beats anything else.
 *
 * @param lengthTol seconds around the playlist length; -1 turns the filter off
 * @param preview ask sockseek to print the search results instead of downloading
 * @param maxSources how many uploaders to try for one track
 */
public record Options(int lengthTol, boolean preview, int maxSources) {

    public static Options defaults() {
        return new Options(3, false, 3);
    }

    public Options withLengthTol(int seconds) {
        return new Options(seconds, preview, maxSources);
    }
}
