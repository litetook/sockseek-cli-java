package dev.litetook.sockseek.config;

import java.util.List;
import java.util.Optional;

/**
 * One tunable number: the config key (its unit is in the name), the command-line flag, the
 * built-in default and the lowest legal value.
 *
 * @param section the block of config.yaml it belongs in - what the value affects, not what it
 *                is about: the whole run, one playlist, one track, the cache. The full name of
 *                a setting is {@code section.key}, and a key found under another section is
 *                still honoured, with a warning: the file groups, it does not decide
 * @param minimum null when any value will do
 * @param oldKeys names an earlier version used, still accepted with a warning
 */
public record Setting(String section, String key, String flag, Kind kind, Number fallback,
                      Double minimum, String help, List<String> oldKeys) {

    public Setting {
        oldKeys = List.copyOf(oldKeys);
    }

    /** {@code playlist.attempts} - how the setting is named in the file and in the run log. */
    public String fullName() {
        return section + "." + key;
    }

    /**
     * How the value reads in the file.
     *
     * <p>A BOOLEAN still travels as a number - 1 or 0 - so everything downstream keeps
     * working on one map of numbers; only the reading and the printing know the difference.
     * The owner asked for one on 2026-10-17: a switch that is only a command-line flag is
     * not a setting, it is something you have to remember every time.
     */
    public enum Kind {
        INTEGER, DECIMAL, BOOLEAN;

        /** The text as this kind of number, or empty when it is not one. */
        public Optional<Number> parse(String text) {
            String value = text.strip();
            if (this == BOOLEAN) {
                // the words a person actually writes in a config file, either case
                return switch (value.toLowerCase(java.util.Locale.ROOT)) {
                    case "true", "yes", "on", "1" -> Optional.of(1);
                    case "false", "no", "off", "0" -> Optional.of(0);
                    default -> Optional.empty();
                };
            }
            // Double.parseDouble would take "4d" and "Infinity", which no config should mean
            if (!value.matches("[+-]?[0-9]*\\.?[0-9]+([eE][+-]?[0-9]+)?")) {
                return Optional.empty();
            }
            try {
                // not a ternary: Java promotes Integer and Double to double in one, so every
                // whole number would come back as 45.0 and print that way in the run log
                if (this == INTEGER) {
                    return Optional.of(Integer.valueOf(value));
                }
                return Optional.of(Double.valueOf(value));
            } catch (NumberFormatException notThisKind) {
                return Optional.empty();
            }
        }
    }
}
