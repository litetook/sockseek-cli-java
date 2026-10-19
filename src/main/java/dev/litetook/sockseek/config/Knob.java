package dev.litetook.sockseek.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One sockseek setting, described once.
 *
 * <p>sockseek's own options are passed through untouched, so this tool has no list of them -
 * and yet two places have to talk about them in words: the block printed before a run, and
 * the settings page. They were written twice on 2026-10-19 and the owner was right to call
 * it out: two tables of the same knowledge drift apart, and the one nobody looks at is the
 * one that goes wrong.
 *
 * @param key sockseek's own name, as it appears in the conf
 * @param block which part of the file it belongs to, and so which group it shows up in
 * @param phrase what it does, with {@code {}} where the value goes - this is the line in the
 *               block before a run
 * @param measured what was measured about it, when anything was; the settings page adds it
 *                 under the phrase, and most keys have nothing to add
 */
public record Knob(String key, String block, String phrase, String measured) {

    /** What the settings page says about this key: the phrase, plus anything measured. */
    public String about() {
        String said = phrase.replace("{}", "…");
        return measured.isEmpty() ? said : said + ". " + measured;
    }

    /** The parts of sockseek's config, and what each one does to a candidate. */
    public static final Map<String, String[]> BLOCKS = blocks(
            "refuse", "hard filters",
            "a candidate that fails one of these is not taken at all",
            "prefer", "ranking",
            "these order the candidates; they never refuse one",
            "pick_source", "picking an uploader",
            "how the uploader for one download is chosen",
            "drop_source", "dropping an uploader",
            "when an uploader is set aside, and how fast",
            "stall", "giving up on a transfer",
            "how long a transfer may send nothing before the next candidate is tried",
            "limit", "sockseek's own search limit",
            "separate from rate_limit, which is this tool's own brake");

    private static Map<String, String[]> blocks(String... triples) {
        Map<String, String[]> all = new LinkedHashMap<>();
        for (int at = 0; at + 2 < triples.length; at += 3) {
            all.put(triples[at], new String[]{triples[at + 1], triples[at + 2]});
        }
        return all;
    }

    /** Every sockseek key this tool has words for. Everything else is passed through raw. */
    public static final List<Knob> ALL = List.of(
            knob("min-bitrate", "refuse", "bitrate at least {} kbps",
                    "a floor refuses the COMMON copies: 256 refused 104 of 135 failures "
                            + "on a real run"),
            knob("max-bitrate", "refuse", "bitrate at most {} kbps", ""),
            knob("max-samplerate", "refuse", "samplerate at most {} Hz",
                    "48000 is CD, so no CD copy is refused by it"),
            knob("max-bitdepth", "refuse", "bitdepth at most {} bit", "16 is CD"),
            knob("format", "refuse", "the format has to be one of: {}",
                    "allowed AT ALL, not an order - the order is track.format_order"),
            knob("strict-conditions", "refuse",
                    "a candidate that does not declare a property is refused",
                    "off, a file that says nothing about itself is still accepted"),
            knob("pref-min-bitrate", "prefer", "{} kbps and up is preferred", ""),
            knob("pref-max-bitrate", "prefer", "above {} kbps ranks last", ""),
            knob("pref-format", "prefer", "formats preferred, in this order: {}",
                    "sockseek's own docs say this list is unordered, which is why "
                            + "track.format_order exists"),
            knob("pref-strict-title", "prefer",
                    "the track title has to appear in the file name", ""),
            knob("pref-strict-artist", "prefer",
                    "the artist has to appear somewhere in the path", ""),
            knob("fast-search", "pick_source", "the first candidate that fits is taken", ""),
            knob("fast-search-delay", "pick_source",
                    "candidates are collected for {} ms first", ""),
            knob("fast-search-min-up-speed", "pick_source",
                    "uploads slower than {} are skipped", "the unit is undocumented and "
                            + "this number is unproven"),
            knob("max-stale-time", "stall",
                    "a transfer with no bytes for {} ms -> on to the next candidate", ""),
            knob("fails-to-downrank", "drop_source", "ranked lower after {} failure(s)", ""),
            knob("fails-to-ignore", "drop_source",
                    "left out for the rest of the run after {} failure(s)", ""),
            knob("searches-per-time", "limit", "sockseek's own limit: {} searches", ""),
            knob("searches-renew-time", "limit", "replenished every {} s", ""));

    private static Knob knob(String key, String block, String phrase, String measured) {
        return new Knob(key, block, phrase, measured);
    }

    private static final Map<String, Knob> BY_KEY = new LinkedHashMap<>();

    static {
        ALL.forEach(knob -> BY_KEY.put(knob.key(), knob));
    }

    public static Optional<Knob> of(String key) {
        return Optional.ofNullable(BY_KEY.get(key));
    }

    /** The keys of one block, in the order they are declared above. */
    public static List<Knob> in(String block) {
        return ALL.stream().filter(knob -> knob.block().equals(block)).toList();
    }

    /** The phrase with the value in it; a switch that is off says nothing at all. */
    public Optional<String> say(String value) {
        String said = value == null ? "" : value.strip();
        if (said.isEmpty()) {
            return Optional.empty();
        }
        if (!phrase.contains("{}")) {
            // a switch: the phrase describes it being ON, so OFF has nothing to report
            return truthy(said) ? Optional.of(phrase) : Optional.empty();
        }
        return Optional.of(phrase.replace("{}", said));
    }

    private static boolean truthy(String value) {
        String said = value.toLowerCase(java.util.Locale.ROOT);
        return said.equals("true") || said.equals("yes") || said.equals("on")
                || said.equals("1");
    }
}
