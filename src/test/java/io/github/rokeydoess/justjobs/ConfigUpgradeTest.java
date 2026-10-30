package io.github.rokeydoess.justjobs;

import java.util.List;
import java.util.Map;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConfigUpgradeTest {
    @Test void ownershipUpdateOnlyRefreshesTheOldBrewingDescription() {
        var yaml = new YamlConfiguration(); yaml.set("config-version", 13);
        yaml.set("jobs.brewing.description", "Make and improve potions with ingredients you load by hand.");
        yaml.set("jobs.brewing.rules", List.of(Map.of("activity", "brew", "materials", List.of("SUGAR"), "xp", 8, "money", "0.2")));
        yaml.set("jobs.digging.rules", List.of(Map.of("activity", "block-break", "materials", List.of("DIRT"), "xp", 9)));
        assertTrue(ConfigUpgrade.jobs(yaml));
        assertEquals("Make and improve potions.", yaml.getString("jobs.brewing.description"));
        assertEquals(8, yaml.getMapList("jobs.brewing.rules").getFirst().get("xp"));
        assertFalse(yaml.contains("jobs.digging.progression")); assertFalse(yaml.contains("jobs.cooking"));
        assertFalse(ConfigUpgrade.jobs(yaml));
        yaml.set("config-version", 13); yaml.set("jobs.brewing.description", "Alchemy"); ConfigUpgrade.jobs(yaml);
        assertEquals("Alchemy", yaml.getString("jobs.brewing.description"));
    }
    @Test void processingAndTravelXpUpgradeWithoutChangingMoneyOrCustomAmounts() {
        var yaml = YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(getClass().getResourceAsStream("/jobs.yml")));
        yaml.set("config-version", 12);
        var oldMoney = new java.util.HashMap<String, List<?>>();
        for (String id : List.of("cooking", "smithing", "exploring")) {
            var rules = new java.util.ArrayList<Map<?, ?>>(yaml.getMapList("jobs." + id + ".rules"));
            oldMoney.put(id, rules.stream().map(r -> r.get("money")).toList());
            for (int i = 0; i < rules.size(); i++) {
                var rule = new java.util.LinkedHashMap<String, Object>(); rules.get(i).forEach((k, v) -> rule.put(k.toString(), v));
                if (List.of("cook", "smelt").contains(rule.get("activity"))) rule.put("xp", 0.5);
                if ("travel".equals(rule.get("activity"))) rule.put("xp", 0.01);
                rules.set(i, rule);
            }
            yaml.set("jobs." + id + ".rules", rules);
        }
        ConfigUpgrade.jobs(yaml);
        for (String id : oldMoney.keySet()) {
            var rules = yaml.getMapList("jobs." + id + ".rules");
            assertEquals(oldMoney.get(id), rules.stream().map(r -> r.get("money")).toList());
            for (var rule : rules) if (List.of("cook", "smelt", "travel").contains(rule.get("activity")))
                assertEquals("travel".equals(rule.get("activity")) ? .02 : 1, ((Number) rule.get("xp")).doubleValue());
        }
    }
    @Test void defaultCurveUpgradesOnceAndCustomOrInvalidValuesRemainIntact() {
        var yaml = new YamlConfiguration(); yaml.set("config-version", 3);
        yaml.set("economy.hourly-income-limit-per-job", "100");
        yaml.set("progression.first-level-xp", 25); yaml.set("progression.early-level-xp", 150); yaml.set("progression.plateau-xp", 2500);
        assertTrue(ConfigUpgrade.settings(yaml));
        assertEquals(10, yaml.getInt("progression.first-level-xp")); assertEquals(40, yaml.getInt("progression.early-level-xp")); assertEquals(200, yaml.getInt("progression.plateau-xp"));
        assertFalse(ConfigUpgrade.settings(yaml));
        assertEquals("100", yaml.getString("economy.hourly-income-limit-per-job"));
        for (Object value : List.of(300, "25", "invalid")) {
            yaml.set("config-version", 3); yaml.set("progression.first-level-xp", value);
            assertFalse(ConfigUpgrade.settings(yaml)); assertEquals(value, yaml.get("progression.first-level-xp"));
        }
    }
    @Test void customizedJobCurvesKeepTheirPreviouslyInheritedQuotas() {
        var yaml = new YamlConfiguration(); yaml.set("config-version", 12);
        yaml.set("jobs.mining.progression.first-level-xp", 300);
        yaml.set("jobs.mining.rules", List.of(Map.of("activity", "block-break", "materials", List.of("STONE"), "xp", 7, "money", "0.4")));
        yaml.set("jobs.digging.progression.xp-multiplier", 2);
        yaml.set("jobs.digging.rules", List.of(Map.of("activity", "block-break", "materials", List.of("DIRT"), "xp", 1, "money", "0.02")));
        ConfigUpgrade.jobs(yaml);
        assertEquals(300, yaml.getInt("jobs.mining.progression.first-level-xp")); assertEquals(300, yaml.getInt("jobs.mining.progression.early-level-xp"));
        assertEquals(2500, yaml.getInt("jobs.mining.progression.plateau-xp"));
        assertEquals(1000, yaml.getInt("jobs.digging.progression.plateau-xp")); assertEquals(2, yaml.getInt("jobs.digging.progression.xp-multiplier"));
        assertEquals("0.4", yaml.getMapList("jobs.mining.rules").getFirst().get("money")); assertEquals(7, yaml.getMapList("jobs.mining.rules").getFirst().get("xp"));
        assertFalse(yaml.contains("jobs.cooking")); assertFalse(yaml.contains("jobs.archaeology"));
        assertFalse(ConfigUpgrade.jobs(yaml));
    }
    @Test void customSharedCurveAndXpRewardsAreNotReplacedByTheDefaultPresets() {
        var yaml = new YamlConfiguration(); yaml.set("config-version", 12);
        yaml.set("jobs.mining.rules", List.of(Map.of("activity", "block-break", "materials", List.of("STONE"), "xp", 1)));
        yaml.set("jobs.cooking.rules", List.of(Map.of("activity", "cook", "materials", List.of("COOKED_SALMON"), "xp", 8, "money", "0.01")));
        ConfigUpgrade.jobs(yaml, new Progression(30, 90, 900, 10, 50, 1), null);
        assertFalse(yaml.contains("jobs.mining.progression"));
        assertEquals(8, yaml.getMapList("jobs.cooking.rules").getFirst().get("xp"));
    }
    @Test void popupAndPotDefaultsUpgradeOnceWithoutReplacingCustomBrushRewards() {
        var yaml = new YamlConfiguration(); yaml.set("config-version", 11);
        yaml.set("jobs.cooking.enabled", false); yaml.set("jobs.smithing.name", "Metalwork");
        yaml.set("jobs.archaeology.description", "Brush buried finds out of suspicious sand and gravel.");
        var brush = Map.of("activity", "brush", "materials", List.of("SUSPICIOUS_SAND"), "xp", 8, "money", "0.35");
        yaml.set("jobs.archaeology.rules", List.of(brush));
        assertTrue(ConfigUpgrade.jobs(yaml));
        assertEquals(30, yaml.getInt("jobs.cooking.boss-bar-cooldown-seconds"));
        assertEquals(30, yaml.getInt("jobs.smithing.boss-bar-cooldown-seconds"));
        assertFalse(yaml.getBoolean("jobs.cooking.enabled"));
        assertEquals("Metalwork", yaml.getString("jobs.smithing.name"));
        assertEquals(brush, yaml.getMapList("jobs.archaeology.rules").getFirst());
        assertEquals(List.of("DECORATED_POT"), yaml.getMapList("jobs.archaeology.rules").getLast().get("materials"));
        assertEquals("0.05", yaml.getMapList("jobs.archaeology.rules").getLast().get("money"));
        assertEquals("Brush buried finds and break natural decorated pots.", yaml.getString("jobs.archaeology.description"));
        assertFalse(yaml.contains("jobs.brewing"));
        assertFalse(ConfigUpgrade.jobs(yaml));
        assertEquals(2, yaml.getMapList("jobs.archaeology.rules").size());
    }
    @Test void customCooldownsAndBlockRulesSurviveAndDeletedJobsStayDeleted() {
        var yaml = new YamlConfiguration(); yaml.set("config-version", 11);
        yaml.set("jobs.smithing.boss-bar-cooldown-seconds", 0);
        yaml.set("jobs.archaeology.description", "Ancient finds");
        var block = Map.of("activity", "BLOCK_BREAK", "materials", List.of("DECORATED_POT"), "money", "0.25");
        yaml.set("jobs.archaeology.rules", List.of(block));
        ConfigUpgrade.jobs(yaml);
        assertEquals(0, yaml.getInt("jobs.smithing.boss-bar-cooldown-seconds"));
        assertEquals(List.of(block), yaml.getMapList("jobs.archaeology.rules"));
        assertEquals("Ancient finds", yaml.getString("jobs.archaeology.description"));
        assertFalse(yaml.contains("jobs.cooking")); assertFalse(yaml.contains("jobs.brewing"));
    }
    @Test void processingJobsUpgradeWithoutRetuningAlreadyUpgradedRewards() {
        var yaml = new YamlConfiguration(); yaml.set("config-version", 10);
        yaml.set("jobs.hunting.rules", List.of(Map.of("activity", "mob-kill", "entities", List.of("#hostile"), "money", "0.1667")));
        yaml.set("jobs.smithing.enabled", false);
        yaml.set("jobs.smithing.rules", List.of(Map.of("activity", "craft", "materials", List.of("IRON_SWORD"), "money", "2")));
        ConfigUpgrade.jobs(yaml);
        assertEquals("0.1667", yaml.getMapList("jobs.hunting.rules").getFirst().get("money"));
        assertFalse(yaml.getBoolean("jobs.smithing.enabled"));
        assertEquals("2", yaml.getMapList("jobs.smithing.rules").getFirst().get("money"));
        assertEquals(1, yaml.getMapList("jobs.smithing.rules").stream().filter(r -> r.get("activity").equals("smelt")).count());
        assertTrue(yaml.contains("jobs.cooking") && yaml.contains("jobs.brewing"));
        assertFalse(ConfigUpgrade.jobs(yaml));
        yaml.set("jobs.cooking", null); ConfigUpgrade.jobs(yaml); assertFalse(yaml.contains("jobs.cooking"));
    }
    @Test void customProcessingJobsAndSmeltingRulesArePreserved() {
        var yaml = new YamlConfiguration(); yaml.set("config-version", 10);
        yaml.set("jobs.brewing.enabled", false); yaml.set("jobs.brewing.rules", List.of(Map.of("activity", "brew", "materials", List.of("SUGAR"), "money", "3")));
        yaml.set("jobs.smithing.rules", List.of(Map.of("activity", "smelt", "materials", List.of("GOLD_INGOT"), "money", "4")));
        ConfigUpgrade.jobs(yaml);
        assertFalse(yaml.getBoolean("jobs.brewing.enabled"));
        assertEquals("3", yaml.getMapList("jobs.brewing.rules").getFirst().get("money"));
        assertEquals(1, yaml.getMapList("jobs.smithing.rules").size());
        assertEquals("4", yaml.getMapList("jobs.smithing.rules").getFirst().get("money"));
    }
    @Test void expandedJobsKeepCustomRulesAndOnlyAddMissingActivitiesOnce() {
        var yaml = new YamlConfiguration(); yaml.set("config-version", 9);
        yaml.set("jobs.farming.name", "Animal Care"); yaml.set("jobs.farming.enabled", false);
        yaml.set("jobs.farming.rules", List.of(Map.of("activity", "shear", "entities", List.of("SHEEP"), "xp", 9, "money", "2")));
        yaml.set("jobs.building.rules", List.of(Map.of("activity", "craft", "materials", List.of("BRICKS"), "money", "3")));
        yaml.set("jobs.exploring.rules", List.of(Map.of("activity", "travel", "money", "0.123")));
        ConfigUpgrade.jobs(yaml);
        assertEquals("Animal Care", yaml.getString("jobs.farming.name")); assertFalse(yaml.getBoolean("jobs.farming.enabled"));
        assertEquals("2", yaml.getMapList("jobs.farming.rules").getFirst().get("money"));
        assertEquals(1, yaml.getMapList("jobs.farming.rules").stream().filter(r -> r.get("activity").equals("shear")).count());
        assertTrue(yaml.getMapList("jobs.farming.rules").stream().noneMatch(r -> java.util.Set.of("breed", "crop-harvest").contains(r.get("activity"))));
        assertTrue(yaml.getMapList("jobs.building.rules").stream().anyMatch(r -> r.get("activity").equals("stonecut")));
        assertEquals("0.123", yaml.getMapList("jobs.exploring.rules").getFirst().get("money"));
        assertTrue(yaml.contains("jobs.archaeology") && yaml.contains("jobs.smithing"));
        int count = yaml.getMapList("jobs.farming.rules").size();
        assertFalse(ConfigUpgrade.jobs(yaml)); assertEquals(count, yaml.getMapList("jobs.farming.rules").size());
        yaml.set("jobs.smithing", null); ConfigUpgrade.jobs(yaml); assertFalse(yaml.contains("jobs.smithing"));
    }
    @Test void conservativeRatesOnlyReplaceMatchingFormerDefaults() {
        var yaml = new YamlConfiguration(); yaml.set("config-version", 9);
        yaml.set("jobs.hunting.rules", List.of(Map.of("activity", "mob-kill", "entities", List.of("#hostile"), "xp", 7, "money", "0.1667")));
        yaml.set("jobs.exploring.rules", List.of(Map.of("activity", "travel", "money", "0.001", "xp", 0.02)));
        yaml.set("jobs.enchanting.rules", List.of(Map.of("activity", "enchant", "money", "0.50", "xp", 4)));
        ConfigUpgrade.jobs(yaml);
        assertEquals("0.05", yaml.getMapList("jobs.hunting.rules").getFirst().get("money"));
        assertEquals(7, yaml.getMapList("jobs.hunting.rules").getFirst().get("xp"));
        assertEquals("0.00075", yaml.getMapList("jobs.exploring.rules").getFirst().get("money"));
        assertEquals("0.35", yaml.getMapList("jobs.enchanting.rules").getFirst().get("money"));
        yaml.set("config-version", 9); yaml.set("jobs.hunting.rules", List.of(Map.of("activity", "mob-kill", "entities", List.of("ZOMBIE"), "money", "0.1667")));
        ConfigUpgrade.jobs(yaml);
        assertEquals("0.1667", yaml.getMapList("jobs.hunting.rules").getFirst().get("money"));
    }
    @Test void lowerDefaultCapKeepsCustomLimitsAndSpawnerPreferences() {
        for (String cap : List.of("100", "100.00", "40", "0")) {
            var yaml = new YamlConfiguration(); yaml.set("config-version", 2);
            yaml.set("economy.hourly-income-limit-per-job", cap); yaml.set("rules.reward-spawner-mobs", true);
            ConfigUpgrade.settings(yaml);
            assertEquals(cap.startsWith("100") ? "25" : cap, yaml.getString("economy.hourly-income-limit-per-job"));
            assertTrue(yaml.getBoolean("rules.reward-spawner-mobs"));
            yaml.set("economy.hourly-income-limit-per-job", "100");
            ConfigUpgrade.settings(yaml); assertEquals("100", yaml.getString("economy.hourly-income-limit-per-job"));
        }
    }
    @Test void oldDefaultsGetNewNamesAndRatesWithoutChangingJobIds() {
        var yaml = new YamlConfiguration();
        yaml.set("jobs.digger.name", "Digger");
        yaml.set("jobs.digger.rules", List.of(Map.of("activity", "block-break", "money", ".04")));
        assertTrue(ConfigUpgrade.jobs(yaml));
        assertEquals("Digging", yaml.getString("jobs.digger.name"));
        assertEquals("0.0075", yaml.getMapList("jobs.digger.rules").getFirst().get("money"));
        assertFalse(ConfigUpgrade.jobs(yaml));
        assertEquals(9, yaml.getConfigurationSection("jobs").getKeys(false).size());
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
        assertEquals(14, yaml.getInt("config-version"));
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
        assertEquals(List.of("0.03", "0.08", "0.35"), yaml.getMapList("jobs.miner.rules").stream().map(r -> r.get("money")).toList());
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
        assertEquals("2.5", yaml.getMapList("jobs.dueling.rules").getFirst().get("money"));
        assertEquals(500, yaml.getInt("jobs.exploring.distance-per-reward"));
        assertEquals("0.006", yaml.getMapList("jobs.mining.rules").getFirst().get("money"));
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
        assertEquals(14, yaml.getInt("config-version"));
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
            assertEquals(4, yaml.getInt("config-version"));
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
            assertEquals("0.05", rule.get("money")); assertEquals(7, rule.get("xp")); assertEquals("Monster", rule.get("description"));
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
        assertEquals("2.5", yaml.getMapList("jobs.dueling.rules").getFirst().get("money"));
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
