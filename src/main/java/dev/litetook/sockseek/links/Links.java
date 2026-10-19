package dev.litetook.sockseek.links;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** What counts as a playlist link, and what its name becomes on disk. */
public final class Links {

    /** The embed page's own ceiling, not ours. */
    public static final int EMBED_MAX = 100;

    private static final Pattern APOSTROPHES = Pattern.compile("['’ʼ`]");
    private static final Pattern NOT_WORD =
            Pattern.compile("[^\\w\\s-]", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern SPACES =
            Pattern.compile("[\\s_]+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern DASHES = Pattern.compile("-{2,}");

    private Links() {
    }

    /**
     * Whether {@code --import} was given a link rather than a file. An http link to a service
     * we cannot read is still a link: it deserves "I cannot read this one", not "no such file".
     */
    public static boolean isLink(String text) {
        String value = text == null ? "" : text.strip().toLowerCase(Locale.ROOT);
        return value.startsWith("http://") || value.startsWith("https://")
                || value.startsWith("spotify:");
    }

    /** "spotify", "soundcloud", "beatport", or empty for a link to something we cannot read. */
    public static Optional<String> detect(String text) {
        String value = text == null ? "" : text.strip();
        if (value.toLowerCase(Locale.ROOT).startsWith("spotify:")) {
            return Optional.of("spotify");
        }
        if (!isLink(value)) {
            return Optional.empty();
        }
        String host;
        try {
            host = java.net.URI.create(value).getHost();
        } catch (IllegalArgumentException notAUri) {
            return Optional.empty();
        }
        if (host == null) {
            return Optional.empty();
        }
        host = host.toLowerCase(Locale.ROOT);
        if (host.equals("spotify.com") || host.endsWith(".spotify.com")) {
            return Optional.of("spotify");
        }
        if (host.equals("soundcloud.com") || host.endsWith(".soundcloud.com")) {
            return Optional.of("soundcloud");
        }
        if (host.equals("beatport.com") || host.endsWith(".beatport.com")) {
            return Optional.of("beatport");
        }
        return Optional.empty();
    }

    /** A playlist name turned into a file name: {@code Lo-Fi Beats (2024)} -> {@code lo-fi-beats-2024}. */
    public static String slug(String name) {
        // an apostrophe is dropped, not turned into a separator: "Today's" -> "todays"
        String cleaned = APOSTROPHES.matcher(name == null ? "" : name).replaceAll("");
        cleaned = NOT_WORD.matcher(cleaned).replaceAll(" ");
        cleaned = SPACES.matcher(cleaned.strip()).replaceAll("-");
        cleaned = DASHES.matcher(cleaned).replaceAll("-");
        return strip(cleaned, '-').toLowerCase(Locale.ROOT);
    }

    private static String strip(String text, char character) {
        int start = 0;
        int end = text.length();
        while (start < end && text.charAt(start) == character) {
            start++;
        }
        while (end > start && text.charAt(end - 1) == character) {
            end--;
        }
        return text.substring(start, end);
    }
}
