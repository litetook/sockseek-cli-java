package dev.litetook.sockseek.config;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * What config.yaml held, after the values were checked.
 *
 * <p>A key that was invalid, out of range or unknown is simply absent: the caller then uses the
 * built-in default, and the warning that says so has already been given. The maps are kept
 * apart because they answer different questions - where things are, how the search behaves,
 * and what is handed to sockseek without being read at all.
 *
 * @param sockseek the sockseek block, in the order the file wrote it, keys as sockseek knows
 *                 them. Nothing here is validated against anything: they are its options, not
 *                 ours, and it is the one that rejects what it does not know
 */
public record Config(Map<String, String> paths, Map<String, Number> numbers,
                     Map<String, String> text, Map<String, String> sockseek) {

    public Config {
        paths = Map.copyOf(paths);
        numbers = Map.copyOf(numbers);
        text = Map.copyOf(text);
        sockseek = new LinkedHashMap<>(sockseek);   // order is what the file said
    }

    public static Config empty() {
        return new Config(Map.of(), Map.of(), Map.of(), Map.of());
    }

    public boolean isEmpty() {
        return paths.isEmpty() && numbers.isEmpty() && text.isEmpty() && sockseek.isEmpty();
    }

    public boolean has(String key) {
        return paths.containsKey(key) || numbers.containsKey(key) || text.containsKey(key);
    }

    public Optional<String> path(String key) {
        return Optional.ofNullable(paths.get(key));
    }

    /** A text setting - format_order is the only one so far. */
    public String text(String key, String fallback) {
        String value = text.get(key);
        return value == null ? fallback : value;
    }

    public OptionalInt integer(String key) {
        Number value = numbers.get(key);
        return value == null ? OptionalInt.empty() : OptionalInt.of(value.intValue());
    }

    public OptionalDouble decimal(String key) {
        Number value = numbers.get(key);
        return value == null ? OptionalDouble.empty() : OptionalDouble.of(value.doubleValue());
    }
}
