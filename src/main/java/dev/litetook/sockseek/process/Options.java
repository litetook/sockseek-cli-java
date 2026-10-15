package dev.litetook.sockseek.process;

/**
 * What the tool itself puts on the sockseek command line.
 *
 * <p>Everything else - the quality floor, the format and bitrate preferences, the waiting
 * windows - is sockseek's own and lives in sockseek.conf. A value here would silently beat that
 * file, which is how pref-min-bitrate in the conf was dead until 2026-10-11.
 *
 * @param lengthTol seconds around the playlist length; -1 turns the filter off
 * @param preview ask sockseek to print the search results instead of downloading
 */
public record Options(int lengthTol, boolean preview) {

    public static Options defaults() {
        return new Options(3, false);
    }

    public Options withLengthTol(int seconds) {
        return new Options(seconds, preview);
    }
}
