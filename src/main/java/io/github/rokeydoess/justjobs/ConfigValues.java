package io.github.rokeydoess.justjobs;

import org.bukkit.configuration.ConfigurationSection;
import java.math.BigDecimal;
import java.util.Set;

final class ConfigValues {
    private ConfigValues() { }
    static void keys(ConfigurationSection section, Set<String> allowed) {
        for (String key : section.getKeys(false)) if (!allowed.contains(key))
            throw new IllegalArgumentException(path(section, key) + " is not a supported setting");
    }
    static String path(ConfigurationSection section, String key) {
        String parent = section.getCurrentPath();
        return parent == null || parent.isEmpty() ? key : parent + "." + key;
    }
    static boolean bool(ConfigurationSection section, String key, boolean fallback) {
        if (!section.contains(key)) return fallback;
        if (!section.isBoolean(key)) throw new IllegalArgumentException(path(section, key) + " must be true or false");
        return section.getBoolean(key);
    }
    static double number(ConfigurationSection section, String key, double fallback) {
        if (!section.contains(key)) return fallback;
        if (!(section.get(key) instanceof Number value) || !Double.isFinite(value.doubleValue()))
            throw new IllegalArgumentException(path(section, key) + " must be a finite number");
        return value.doubleValue();
    }
    static String text(ConfigurationSection section, String key, String fallback, int maximumLength) {
        if (!section.contains(key)) return fallback;
        if (!(section.get(key) instanceof String value) || value.codePointCount(0, value.length()) > maximumLength
                || value.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException(path(section, key) + " must be text on one line, at most " + maximumLength + " characters");
        return value;
    }
    static long integer(ConfigurationSection section, String key, long fallback, long min, long max) {
        double value = number(section, key, fallback);
        if (value != Math.rint(value) || value < min || value > max)
            throw new IllegalArgumentException(path(section, key) + " must be a whole number between " + min + " and " + max);
        return (long) value;
    }
    static BigDecimal money(ConfigurationSection section, String key, BigDecimal fallback) {
        if (!section.contains(key)) return fallback;
        try {
            BigDecimal value = new BigDecimal(String.valueOf(section.get(key)));
            if (value.signum() < 0 || value.compareTo(new BigDecimal("1000000")) > 0 || value.scale() > 8) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(path(section, key) + " must be between 0 and 1000000, with at most 8 decimal places");
        }
    }
    static java.util.List<String> strings(ConfigurationSection section, String key) {
        if (!section.contains(key)) return java.util.List.of();
        var list = section.getList(key);
        if (list == null || list.stream().anyMatch(value -> !(value instanceof String)))
            throw new IllegalArgumentException(path(section, key) + " must be a list of names");
        return section.getStringList(key);
    }
}
