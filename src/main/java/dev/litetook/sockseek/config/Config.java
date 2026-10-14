package dev.litetook.sockseek.config;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * What config.yaml held, after the values were checked.
 *
 * <p>A key that was invalid, out of range or unknown is simply absent: the caller then uses the
 * built-in default, and the warning that says so has already been given. The two maps are kept
 * apart because they answer different questions - where things are, and how the search behaves.
 */
public record Config(Map<String, String> paths, Map<String, Number> numbers) {

    public Config {
        paths = Map.copyOf(paths);
        numbers = Map.copyOf(numbers);
    }

    public static Config empty() {
        return new Config(Map.of(), Map.of());
    }

    public boolean isEmpty() {
        return paths.isEmpty() && numbers.isEmpty();
    }

    public boolean has(String key) {
        return paths.containsKey(key) || numbers.containsKey(key);
    }

    public Optional<String> path(String key) {
        return Optional.ofNullable(paths.get(key));
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
