package io.github.rokeydoess.justjobs;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;
import net.kyori.adventure.bossbar.BossBar;
import static org.junit.jupiter.api.Assertions.*;

class JobRuleTest {
    @Test void activityIndexPreservesOverlappingRulesJobsAndMatchingOrder() {
        var dirt = new JobRegistry.Rule(JobRegistry.Activity.BLOCK_BREAK, Set.of(Material.DIRT), Set.of(), false, 1, BigDecimal.ONE, "", Set.of(), Set.of());
        var sand = new JobRegistry.Rule(JobRegistry.Activity.BLOCK_BREAK, Set.of(Material.SAND), Set.of(), false, 2, BigDecimal.TEN, "", Set.of(), Set.of(), true);
        var travel = new JobRegistry.Rule(JobRegistry.Activity.TRAVEL, Set.of(), Set.of(), true, 1, BigDecimal.ZERO, "", Set.of(), Set.of());
        var wildcard = new JobRegistry.Rule(JobRegistry.Activity.BLOCK_BREAK, Set.of(), Set.of(), true, 3, BigDecimal.ONE, "", Set.of(), Set.of());
        var first = new JobRegistry.Job("first", "First", "", Material.DIRT, BossBar.Color.BLUE, true, null, 500, 0, Progression.defaults(), List.of(dirt, travel, sand));
        var second = new JobRegistry.Job("second", "Second", "", Material.SAND, BossBar.Color.RED, false, null, 150, 0, Progression.defaults(), List.of(wildcard));
        var input = new ArrayList<>(List.of(first, second)); var registry = new JobRegistry(input); input.clear();
        assertEquals(List.of(first, second), registry.jobs());
        assertEquals(List.of(dirt, sand), registry.rewards(JobRegistry.Activity.BLOCK_BREAK).getFirst().rules());
        assertEquals(second, registry.rewards(JobRegistry.Activity.BLOCK_BREAK).getLast().job());
        assertEquals(List.of(travel), registry.rewards(JobRegistry.Activity.TRAVEL).getFirst().rules());
        for (var activity : JobRegistry.Activity.values()) for (var material : new Material[]{null, Material.DIRT, Material.SAND, Material.STONE}) {
            boolean expected = registry.jobs().stream().flatMap(job -> job.rules().stream())
                    .anyMatch(rule -> rule.matches(activity, material, null) && (rule.xp() > 0 || rule.money().signum() > 0));
            assertEquals(expected, registry.rewards(activity, material, null));
        }
        assertSame(first, registry.get("first")); assertNull(registry.get(null));
        assertThrows(UnsupportedOperationException.class, () -> registry.rewards(JobRegistry.Activity.BLOCK_BREAK).clear());
        assertThrows(UnsupportedOperationException.class, () -> registry.rewards(JobRegistry.Activity.BLOCK_BREAK).getFirst().rules().clear());
    }
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
