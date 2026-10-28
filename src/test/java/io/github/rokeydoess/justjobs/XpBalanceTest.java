package io.github.rokeydoess.justjobs;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class XpBalanceTest {
    private final YamlConfiguration jobs = YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(getClass().getResourceAsStream("/jobs.yml")));
    private Progression curve(String id) {
        var section = jobs.getConfigurationSection("jobs." + id + ".progression");
        var shared = Progression.defaults();
        return section == null ? shared : new Progression(section.getDouble("first-level-xp", shared.first()),
                section.getDouble("early-level-xp", shared.early()), section.getDouble("plateau-xp", shared.plateau()),
                section.getInt("early-level", shared.earlyLevel()), section.getInt("plateau-level", shared.plateauLevel()), section.getDouble("xp-multiplier", 1));
    }
    private double threshold(Progression curve, int level) {
        double total = 0; for (int i = 1; i < level; i++) total += curve.required(i); return total;
    }
    @Test void aStackPairOfCookedFoodOrIngotsMakesSeveralEarlyLevels() {
        for (String id : java.util.List.of("cooking", "smithing")) {
            var rule = jobs.getMapList("jobs." + id + ".rules").stream().filter(r -> java.util.Set.of("cook", "smelt").contains(r.get("activity"))).findFirst().orElseThrow();
            double xp = ((Number) rule.get("xp")).doubleValue(); var curve = curve(id);
            assertEquals(2, curve.progress(10 * xp).level());
            assertEquals(3, curve.progress(24 * xp).level());
            assertEquals(7, curve.progress(128 * xp).level());
            assertEquals(200, curve.required(50)); assertEquals(200, curve.required(250));
        }
    }
    @Test void everyDefaultJobHasReachableMilestonesAndAStablePlateau() {
        var old = new Progression(25, 150, 2500, 10, 50, 1);
        for (String id : jobs.getConfigurationSection("jobs").getKeys(false)) {
            var curve = curve(id);
            assertTrue(threshold(curve, 50) < threshold(old, 50), id);
            assertTrue(threshold(curve, 250) < threshold(old, 250), id);
            assertEquals(250, curve.progress(threshold(curve, 250)).level(), id);
            assertEquals(curve.required(50), curve.required(10_000), id);
            assertTrue(curve.required(1) < curve.required(10), id);
        }
        assertTrue(curve("mining").required(50) > curve("cooking").required(50));
        assertEquals(50, curve("archaeology").required(50));
    }
}
