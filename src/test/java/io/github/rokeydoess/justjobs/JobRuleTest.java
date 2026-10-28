package io.github.rokeydoess.justjobs;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class JobRuleTest {
    @Test void operationsNeverMatchOtherActivitiesAndNeedTheCorrectOperation() {
        var rule = new JobRegistry.Rule(JobRegistry.Activity.SMITH, Set.of(), Set.of(), true, 1, BigDecimal.ONE, "",
                Set.of(), Set.of("upgrade"));
        assertFalse(rule.matches(JobRegistry.Activity.BLOCK_BREAK, Material.STONE, null));
        assertFalse(rule.matches(JobRegistry.Activity.SMITH, Material.NETHERITE_CHESTPLATE, null));
        assertFalse(rule.matches(JobRegistry.Activity.SMITH, Material.NETHERITE_CHESTPLATE, null, "trim"));
        assertTrue(rule.matches(JobRegistry.Activity.SMITH, Material.NETHERITE_CHESTPLATE, null, "upgrade"));
    }
    @Test void biomeSelectorsMatchNamespacedNamesWithoutAffectingOtherActivities() {
        var rule = new JobRegistry.Rule(JobRegistry.Activity.BIOME_DISCOVER, Set.of(), Set.of(), false, 1, BigDecimal.ONE, "",
                Set.of("minecraft:forest"), Set.of());
        assertTrue(rule.matches(JobRegistry.Activity.BIOME_DISCOVER, null, null, "minecraft:forest"));
        assertFalse(rule.matches(JobRegistry.Activity.BIOME_DISCOVER, null, null, "minecraft:desert"));
        assertFalse(rule.matches(JobRegistry.Activity.BIOME_DISCOVER, null, null));
        assertFalse(rule.matches(JobRegistry.Activity.TRAVEL, null, null, "minecraft:forest"));
    }
    @Test void animalSelectorsMatchTheSpecifiedAnimal() {
        var rule = new JobRegistry.Rule(JobRegistry.Activity.SHEAR, Set.of(), Set.of(EntityType.SHEEP), false, 1, BigDecimal.ONE, "", Set.of(), Set.of());
        assertTrue(rule.matches(JobRegistry.Activity.SHEAR, null, EntityType.SHEEP));
        assertFalse(rule.matches(JobRegistry.Activity.SHEAR, null, EntityType.MOOSHROOM));
        assertFalse(rule.matches(JobRegistry.Activity.TAME, null, EntityType.SHEEP));
    }
}
