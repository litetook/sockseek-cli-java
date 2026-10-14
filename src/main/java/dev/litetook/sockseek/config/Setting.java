package dev.litetook.sockseek.config;

import java.util.List;
import java.util.Optional;

/**
 * One tunable number: the config key (its unit is in the name), the command-line flag, the
 * built-in default and the lowest legal value.
 *
 * @param minimum null when any value will do
 * @param oldKeys names an earlier version used, still accepted with a warning
 */
public record Setting(String key, String flag, Kind kind, Number fallback, Double minimum,
                      String help, List<String> oldKeys) {

    public Setting {
        oldKeys = List.copyOf(oldKeys);
    }

    /** All of them are numbers; a boolean one would need its own handling on the command line. */
    public enum Kind {
        INTEGER, DECIMAL;

        /** The text as this kind of number, or empty when it is not one. */
        public Optional<Number> parse(String text) {
            String value = text.strip();
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
