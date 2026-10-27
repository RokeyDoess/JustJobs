package io.github.rokeydoess.justjobs;

import java.util.List;
import java.util.Map;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConfigUpgradeTest {
    @Test void oldDefaultsGetNewNamesAndRatesWithoutChangingJobIds() {
        var yaml = new YamlConfiguration();
        yaml.set("jobs.digger.name", "Digger");
        yaml.set("jobs.digger.rules", List.of(Map.of("activity", "block-break", "money", ".04")));
        assertTrue(ConfigUpgrade.jobs(yaml));
        assertEquals("Digging", yaml.getString("jobs.digger.name"));
        assertEquals("0.0075", yaml.getMapList("jobs.digger.rules").getFirst().get("money"));
        assertFalse(ConfigUpgrade.jobs(yaml));
        assertEquals(5, yaml.getConfigurationSection("jobs").getKeys(false).size());
    }
    @Test void customizedNamesAndRewardsArePreserved() {
        var yaml = new YamlConfiguration();
        yaml.set("jobs.digger.name", "Excavation");
        yaml.set("jobs.digger.rules", List.of(Map.of("activity", "block-break", "money", "3.14")));
        ConfigUpgrade.jobs(yaml);
        assertEquals("Excavation", yaml.getString("jobs.digger.name"));
        assertEquals("3.14", yaml.getMapList("jobs.digger.rules").getFirst().get("money"));
    }
    @Test void firstSnapshotDefaultsAreRetunedButCustomIntervalsAndRewardsStay() {
        var yaml = new YamlConfiguration(); yaml.set("config-version", 1);
        yaml.set("jobs.digger.rules", List.of(Map.of("activity", "block-break", "money", "0.08")));
        yaml.set("jobs.explorer.rules", List.of(Map.of("activity", "travel", "money", "0.003")));
        yaml.set("jobs.explorer.distance-per-reward", 250);
        yaml.set("jobs.sailor.rules", List.of(Map.of("activity", "sail", "money", "0.123")));
        yaml.set("jobs.sailor.distance-per-reward", 300);
        ConfigUpgrade.jobs(yaml);
        assertEquals(9, yaml.getInt("config-version"));
        assertEquals("0.0075", yaml.getMapList("jobs.digger.rules").getFirst().get("money"));
        assertEquals(500, yaml.getInt("jobs.explorer.distance-per-reward"));
        assertEquals(300, yaml.getInt("jobs.sailor.distance-per-reward"));
        assertEquals("0.123", yaml.getMapList("jobs.sailor.rules").getFirst().get("money"));
    }
    @Test void oreTiersKeepCustomXpAndOnlyReplaceTheOriginalOreSelection() throws Exception {
        var yaml = YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(getClass().getResourceAsStream("/jobs.yml")));
        var tiers = yaml.getMapList("jobs.mining.rules");
        var ores = new java.util.ArrayList<>();
        for (var tier : tiers.subList(1, 4)) ores.addAll((List<?>) tier.get("materials"));
        yaml.set("config-version", 1);
        yaml.set("jobs.miner.rules", List.of(Map.of("activity", "block-break", "materials", ores, "xp", 8, "money", ".75")));
        ConfigUpgrade.jobs(yaml);
        assertEquals(3, yaml.getMapList("jobs.miner.rules").size());
        assertTrue(yaml.getMapList("jobs.miner.rules").stream().allMatch(r -> r.get("xp").equals(8)));
        assertEquals(List.of("0.03", "0.10", "0.50"), yaml.getMapList("jobs.miner.rules").stream().map(r -> r.get("money")).toList());
        yaml.set("config-version", 1);
        yaml.set("jobs.miner.rules", List.of(Map.of("activity", "block-break", "materials", List.of("DIAMOND_ORE"), "xp", 8, "money", ".75")));
        ConfigUpgrade.jobs(yaml);
        assertEquals(".75", yaml.getMapList("jobs.miner.rules").getFirst().get("money"));
    }
    @Test void oldGrowthSettingIsReplacedWithoutResettingCustomQuotas() {
        var yaml = new YamlConfiguration(); yaml.set("progression.growth", .01);
        yaml.set("progression.first-level-xp", 50); yaml.set("progression.plateau-xp", 1500);
        ConfigUpgrade.settings(yaml);
        assertFalse(yaml.contains("progression.growth"));
        assertEquals(50, yaml.getInt("progression.first-level-xp"));
        assertEquals(1500, yaml.getInt("progression.plateau-xp"));
        assertEquals(150, yaml.getInt("progression.early-level-xp"));
    }
    @Test void newJobsAreAddedOnceWithoutReplacingExistingJobs() {
        var yaml = new YamlConfiguration(); yaml.set("config-version", 2);
        yaml.set("jobs.mining.enabled", false);
        yaml.set("jobs.farming.name", "My Farm"); yaml.set("jobs.farming.enabled", false);
        assertTrue(ConfigUpgrade.jobs(yaml));
        assertFalse(yaml.getBoolean("jobs.mining.enabled"));
        assertEquals("My Farm", yaml.getString("jobs.farming.name"));
        assertFalse(yaml.getBoolean("jobs.farming.enabled"));
        assertTrue(yaml.isConfigurationSection("jobs.fishing"));
        assertEquals("fish", yaml.getMapList("jobs.fishing.rules").getFirst().get("activity"));
        yaml.set("jobs.building", null);
        assertFalse(ConfigUpgrade.jobs(yaml));
        assertFalse(yaml.contains("jobs.building"));
    }
    @Test void missingOrMalformedJobsAreStillReportedByNormalValidation() {
        var yaml = new YamlConfiguration(); yaml.set("jobs", "invalid");
        ConfigUpgrade.jobs(yaml);
        assertEquals("invalid", yaml.getString("jobs"));
        var empty = new YamlConfiguration(); ConfigUpgrade.jobs(empty);
        assertFalse(empty.contains("jobs"));
    }
    @Test void descriptionsFollowSelectorsWithoutReplacingOwnerLabelsOrRestoringDeletedJobs() {
        var yaml = new YamlConfiguration(); yaml.set("config-version", 3);
        yaml.set("jobs.engineering.rules", List.of(Map.of("activity", "block-place", "materials", List.of("PISTON"), "xp", 1)));
        yaml.set("jobs.mining.rules", List.of(Map.of("activity", "block-break", "materials", List.of("STONE"), "description", "My stone", "xp", 1)));
        yaml.set("jobs.trading.rules", List.of(Map.of("activity", "trade", "money", "123")));
        ConfigUpgrade.jobs(yaml);
        assertFalse(yaml.contains("jobs.building"));
        assertFalse(yaml.getMapList("jobs.engineering.rules").getFirst().containsKey("description"));
        assertEquals("My stone", yaml.getMapList("jobs.mining.rules").getFirst().get("description"));
        assertEquals("emerald spent", yaml.getMapList("jobs.trading.rules").getFirst().get("description"));
        assertEquals("123", yaml.getMapList("jobs.trading.rules").getFirst().get("money"));
    }
    @Test void anOldFlatCurveWithALowQuotaRemainsValid() {
        var yaml = new YamlConfiguration(); yaml.set("progression.first-level-xp", 10); yaml.set("progression.plateau-xp", 10);
        ConfigUpgrade.settings(yaml);
        assertEquals(10, yaml.getInt("progression.first-level-xp"));
        assertEquals(10, yaml.getInt("progression.early-level-xp"));
    }
    @Test void invalidJobSectionsAreNotOverwrittenByMigration() {
        var yaml = new YamlConfiguration(); yaml.set("jobs.gladiator.progression", "invalid");
        ConfigUpgrade.jobs(yaml);
        assertEquals("invalid", yaml.getString("jobs.gladiator.progression"));
    }
    @Test void originalPlacementDefaultsBecomeRestrictedCraftingWithoutResettingProgressSettings() {
        var yaml = YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(getClass().getResourceAsStream("/placement-jobs.yml")));
        yaml.set("jobs.building.name", "Masonry"); yaml.set("jobs.building.enabled", false);
        yaml.set("jobs.building.progression.xp-multiplier", 2);
        var old = new java.util.LinkedHashMap<String, Object>();
        yaml.getMapList("jobs.building.rules").getFirst().forEach((key, value) -> old.put(key.toString(), value));
        old.put("money", "0.123"); old.put("xp", 7);
        yaml.set("jobs.building.rules", List.of(old));
        assertTrue(ConfigUpgrade.jobs(yaml));
        var rule = yaml.getMapList("jobs.building.rules").getFirst();
        assertEquals("craft", rule.get("activity"));
        assertEquals("0.123", rule.get("money")); assertEquals(7, rule.get("xp"));
        assertEquals(13, ((List<?>) rule.get("materials")).size());
        assertFalse(((List<?>) rule.get("materials")).contains("#minecraft:planks"));
        assertEquals("Masonry", yaml.getString("jobs.building.name")); assertFalse(yaml.getBoolean("jobs.building.enabled"));
        assertEquals(2, yaml.getInt("jobs.building.progression.xp-multiplier"));
        assertTrue(yaml.getMapList("jobs.engineering.rules").stream().allMatch(r -> r.get("activity").equals("craft")));
        assertEquals(List.of("REDSTONE_TORCH", "REPEATER", "RAIL", "TRIPWIRE_HOOK"), yaml.getMapList("jobs.engineering.rules").getFirst().get("materials"));
        assertFalse(ConfigUpgrade.jobs(yaml));
    }
    @Test void customizedPlacementSelectorsAndLabelsSurviveTheCraftingUpgrade() {
        var yaml = new YamlConfiguration(); yaml.set("config-version", 4);
        yaml.set("jobs.building.rules", List.of(Map.of("activity", "block-place", "materials", List.of("BRICKS"), "description", "My bricks", "money", "9")));
        yaml.set("jobs.engineering.rules", List.of(Map.of("activity", "block-place", "materials", List.of("REDSTONE_WIRE", "REDSTONE_WALL_TORCH"), "xp", 8)));
        yaml.set("jobs.custom_placement.rules", List.of(Map.of("activity", "block-place", "materials", List.of("DIRT"))));
        ConfigUpgrade.jobs(yaml);
        assertEquals(List.of("BRICKS"), yaml.getMapList("jobs.building.rules").getFirst().get("materials"));
        assertEquals("My bricks", yaml.getMapList("jobs.building.rules").getFirst().get("description"));
        assertEquals(List.of("REDSTONE", "REDSTONE_TORCH"), yaml.getMapList("jobs.engineering.rules").getFirst().get("materials"));
        assertEquals("block-place", yaml.getMapList("jobs.custom_placement.rules").getFirst().get("activity"));
    }
    @Test void malformedPlacementSelectorsRemainAvailableForNormalValidation() {
        var yaml = new YamlConfiguration(); yaml.set("config-version", 4);
        yaml.set("jobs.engineering.rules", List.of(Map.of("activity", "block-place", "materials", List.of(123))));
        assertDoesNotThrow(() -> ConfigUpgrade.jobs(yaml));
        assertEquals(List.of(123), yaml.getMapList("jobs.engineering.rules").getFirst().get("materials"));
    }
    @Test void stableBalanceChangesOnlyReplaceRecognizedDefaults() {
        var yaml = YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(getClass().getResourceAsStream("/placement-jobs.yml")));
        yaml.set("config-version", 5);
        assertTrue(ConfigUpgrade.jobs(yaml));
        assertEquals("0.0085", yaml.getMapList("jobs.digging.rules").getFirst().get("money"));
        assertEquals("5", yaml.getMapList("jobs.dueling.rules").getFirst().get("money"));
        assertEquals(500, yaml.getInt("jobs.exploring.distance-per-reward"));
        assertEquals("0.0075", yaml.getMapList("jobs.mining.rules").getFirst().get("money"));
        yaml.set("config-version", 5); yaml.set("jobs.exploring.distance-per-reward", 700);
        yaml.set("jobs.digging.rules", List.of(Map.of("activity", "block-break", "materials", List.of("DIRT"), "money", "4")));
        yaml.set("jobs.dueling.rules", List.of(Map.of("activity", "player-kill", "money", "50")));
        ConfigUpgrade.jobs(yaml);
        assertEquals(700, yaml.getInt("jobs.exploring.distance-per-reward"));
        assertEquals("4", yaml.getMapList("jobs.digging.rules").getFirst().get("money"));
        assertEquals("50", yaml.getMapList("jobs.dueling.rules").getFirst().get("money"));
    }
    @Test void stableDefaultsUpgradeWithoutChangingXpOrRepeatingEarlierBalanceChanges() {
        var yaml = YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(getClass().getResourceAsStream("/jobs.yml")));
        yaml.set("config-version", 6);
        yaml.set("jobs.fishing.rules", List.of(Map.of("activity", "fish", "materials", List.of("*"), "money", "0.75", "xp", 9, "description", "Rod catch")));
        yaml.set("jobs.exploring.distance-per-reward", 200);
        yaml.set("jobs.sailing.distance-per-reward", 200);
        var digging = new java.util.LinkedHashMap<String, Object>();
        yaml.getMapList("jobs.digging.rules").getFirst().forEach((key, value) -> digging.put(key.toString(), value));
        digging.put("money", "0.0075"); yaml.set("jobs.digging.rules", List.of(digging));
        assertTrue(ConfigUpgrade.jobs(yaml));
        var fishing = yaml.getMapList("jobs.fishing.rules").getFirst();
        assertEquals("0.25", fishing.get("money"));
        assertEquals(9, fishing.get("xp"));
        assertEquals("Rod catch", fishing.get("description"));
        assertEquals(500, yaml.getInt("jobs.exploring.distance-per-reward"));
        assertEquals(500, yaml.getInt("jobs.sailing.distance-per-reward"));
        assertEquals("0.0075", yaml.getMapList("jobs.digging.rules").getFirst().get("money"));
        assertEquals(9, yaml.getInt("config-version"));
        assertFalse(ConfigUpgrade.jobs(yaml));
    }
    @Test void fishingAndTravelUpgradeKeepsCustomRewardsSelectorsAndIntervals() {
        var yaml = YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(getClass().getResourceAsStream("/jobs.yml")));
        yaml.set("config-version", 6);
        yaml.set("jobs.fishing.rules", List.of(Map.of("activity", "fish", "materials", List.of("*"), "money", "1.50", "xp", 5)));
        yaml.set("jobs.exploring.distance-per-reward", 150);
        yaml.set("jobs.sailing.distance-per-reward", 900);
        ConfigUpgrade.jobs(yaml);
        assertEquals("1.50", yaml.getMapList("jobs.fishing.rules").getFirst().get("money"));
        assertEquals(150, yaml.getInt("jobs.exploring.distance-per-reward"));
        assertEquals(900, yaml.getInt("jobs.sailing.distance-per-reward"));
        yaml.set("config-version", 6);
        yaml.set("jobs.fishing.rules", List.of(Map.of("activity", "fish", "materials", List.of("COD"), "money", "0.75", "xp", 5)));
        ConfigUpgrade.jobs(yaml);
        assertEquals("0.75", yaml.getMapList("jobs.fishing.rules").getFirst().get("money"));
        assertEquals(List.of("COD"), yaml.getMapList("jobs.fishing.rules").getFirst().get("materials"));
    }
    @Test void spawnerExclusionUpgradesOnceAndCanBeDisabledAfterward() {
        for (int version : List.of(0, 1)) {
            var yaml = new YamlConfiguration(); yaml.set("config-version", version);
            yaml.set("rules.reward-spawner-mobs", true);
            ConfigUpgrade.settings(yaml);
            assertFalse(yaml.getBoolean("rules.reward-spawner-mobs"));
            assertEquals(2, yaml.getInt("config-version"));
            yaml.set("rules.reward-spawner-mobs", true);
            ConfigUpgrade.settings(yaml);
            assertTrue(yaml.getBoolean("rules.reward-spawner-mobs"));
        }
        var yaml = new YamlConfiguration(); yaml.set("config-version", 1);
        yaml.set("rules.reward-spawner-mobs", false);
        ConfigUpgrade.settings(yaml);
        assertFalse(yaml.getBoolean("rules.reward-spawner-mobs"));
        yaml.set("config-version", 1); yaml.set("rules.reward-spawner-mobs", "invalid");
        ConfigUpgrade.settings(yaml);
        assertEquals("invalid", yaml.get("rules.reward-spawner-mobs"));
    }
    @Test void huntingUpgradeKeepsCustomRulesAndOnlyRunsOnce() {
        for (String id : List.of("hunting", "hunter")) {
            var yaml = new YamlConfiguration(); yaml.set("config-version", 8);
            yaml.set("jobs." + id + ".rules", List.of(Map.of("activity", "mob-kill", "entities", List.of("#hostile"), "money", "0.40", "xp", 7, "description", "Monster")));
            assertTrue(ConfigUpgrade.jobs(yaml));
            var rule = yaml.getMapList("jobs." + id + ".rules").getFirst();
            assertEquals("0.1667", rule.get("money")); assertEquals(7, rule.get("xp")); assertEquals("Monster", rule.get("description"));
            assertFalse(ConfigUpgrade.jobs(yaml));
            yaml.set("config-version", 8);
            yaml.set("jobs." + id + ".rules", List.of(Map.of("activity", "mob-kill", "entities", List.of("#hostile"), "money", "2", "xp", 5)));
            ConfigUpgrade.jobs(yaml);
            assertEquals("2", yaml.getMapList("jobs." + id + ".rules").getFirst().get("money"));
            yaml.set("config-version", 8);
            yaml.set("jobs." + id + ".rules", List.of(Map.of("activity", "mob-kill", "entities", List.of("ZOMBIE"), "money", "0.40", "xp", 5)));
            ConfigUpgrade.jobs(yaml);
            assertEquals("0.40", yaml.getMapList("jobs." + id + ".rules").getFirst().get("money"));
        }
        var yaml = new YamlConfiguration(); yaml.set("config-version", 8);
        yaml.set("jobs.dueling.rules", List.of(Map.of("activity", "player-kill", "money", "10", "xp", 10)));
        ConfigUpgrade.jobs(yaml);
        assertEquals("10", yaml.getMapList("jobs.dueling.rules").getFirst().get("money"));
    }
    @Test void duelingUpgradePreservesXpAndDoesNotRepeatEarlierDefaultChanges() {
        var yaml = YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(getClass().getResourceAsStream("/jobs.yml")));
        yaml.set("config-version", 7);
        yaml.set("jobs.dueling.rules", List.of(Map.of("activity", "player-kill", "xp", 15, "money", "10")));
        yaml.set("jobs.fishing.rules", List.of(Map.of("activity", "fish", "materials", List.of("*"), "money", "0.75", "xp", 5)));
        yaml.set("jobs.exploring.distance-per-reward", 200);
        yaml.set("jobs.sailing.distance-per-reward", 200);
        assertTrue(ConfigUpgrade.jobs(yaml));
        assertEquals("5", yaml.getMapList("jobs.dueling.rules").getFirst().get("money"));
        assertEquals(15, yaml.getMapList("jobs.dueling.rules").getFirst().get("xp"));
        assertEquals("0.75", yaml.getMapList("jobs.fishing.rules").getFirst().get("money"));
        assertEquals(200, yaml.getInt("jobs.exploring.distance-per-reward"));
        assertEquals(200, yaml.getInt("jobs.sailing.distance-per-reward"));
        assertFalse(ConfigUpgrade.jobs(yaml));
        yaml.set("config-version", 7);
        yaml.set("jobs.dueling.rules", List.of(Map.of("activity", "player-kill", "xp", 15, "money", "20")));
        ConfigUpgrade.jobs(yaml);
        assertEquals("20", yaml.getMapList("jobs.dueling.rules").getFirst().get("money"));
    }
}
