package io.github.rokeydoess.justjobs;

import java.math.BigDecimal;
import java.util.Set;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConfigValuesTest {
    @Test void displayTextAcceptsSymbolsButRejectsWrongTypesAndMultipleLines() {
        var yaml = new YamlConfiguration();
        assertEquals("$", ConfigValues.text(yaml, "symbol", "$", 16));
        yaml.set("symbol", "£"); assertEquals("£", ConfigValues.text(yaml, "symbol", "$", 16));
        yaml.set("symbol", ""); assertEquals("", ConfigValues.text(yaml, "symbol", "$", 16));
        yaml.set("symbol", 12); assertThrows(IllegalArgumentException.class, () -> ConfigValues.text(yaml, "symbol", "$", 16));
        yaml.set("symbol", "bad\nlabel"); assertThrows(IllegalArgumentException.class, () -> ConfigValues.text(yaml, "symbol", "$", 16));
    }
    @Test void mistypedBooleansAreRejectedRatherThanBecomingDefaults() {
        var yaml = new YamlConfiguration(); yaml.set("feedback.boss-bar", "yes please");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> ConfigValues.bool(yaml, "feedback.boss-bar", true)).getMessage().contains("feedback.boss-bar"));
    }
    @Test void fractionalOrTextIntervalsAreRejected() {
        var yaml = new YamlConfiguration(); yaml.set("seconds", 1.5);
        assertThrows(IllegalArgumentException.class, () -> ConfigValues.integer(yaml, "seconds", 10, 1, 86400));
        yaml.set("seconds", "ten");
        assertThrows(IllegalArgumentException.class, () -> ConfigValues.integer(yaml, "seconds", 10, 1, 86400));
    }
    @Test void moneyCanBeQuotedOrNumericButMustNotBeNegative() {
        var yaml = new YamlConfiguration(); yaml.set("cap", "100.50");
        assertEquals(new BigDecimal("100.50"), ConfigValues.money(yaml, "cap", BigDecimal.ZERO));
        yaml.set("cap", 100);
        assertEquals(new BigDecimal("100"), ConfigValues.money(yaml, "cap", BigDecimal.ZERO));
        yaml.set("cap", -1);
        assertThrows(IllegalArgumentException.class, () -> ConfigValues.money(yaml, "cap", BigDecimal.ZERO));
    }
    @Test void unknownKeysAndIncorrectListsIdentifyTheirLocation() {
        var yaml = new YamlConfiguration(); yaml.set("rules.matrials", "DIRT");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> ConfigValues.keys(yaml.getConfigurationSection("rules"), Set.of("materials"))).getMessage().contains("rules.matrials"));
        yaml.set("rules.materials", "DIRT");
        assertThrows(IllegalArgumentException.class, () -> ConfigValues.strings(yaml, "rules.materials"));
    }
}
