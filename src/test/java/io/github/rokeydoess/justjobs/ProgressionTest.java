package io.github.rokeydoess.justjobs;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProgressionTest {
    private final Progression curve = new Progression(10, 100, 2500, 10, 50, 1);

    @Test void milestonesOnlyAnnounceWhenCrossingTheConfiguredLevel() {
        assertEquals(0, Progression.milestoneCrossed(1, 49, 50));
        assertEquals(50, Progression.milestoneCrossed(49, 50, 50));
        assertEquals(0, Progression.milestoneCrossed(50, 51, 50));
        assertEquals(100, Progression.milestoneCrossed(99, 100, 50));
        assertEquals(10, Progression.milestoneCrossed(9, 10, 10));
        assertEquals(2, Progression.milestoneCrossed(1, 2, 1));
    }
    @Test void bulkLevelGainsUseOnlyTheHighestCrossedMilestone() {
        assertEquals(100, Progression.milestoneCrossed(49, 125, 50));
        assertEquals(150, Progression.milestoneCrossed(49, 150, 50));
        assertEquals(0, Progression.milestoneCrossed(100, 125, 50));
        assertEquals(2_147_483_600, Progression.milestoneCrossed(100, Integer.MAX_VALUE, 50));
        assertEquals(Integer.MAX_VALUE, Progression.milestoneCrossed(1, Integer.MAX_VALUE, Integer.MAX_VALUE));
    }
    @Test void disabledAndUnchangedLevelsNeverAnnounce() {
        assertEquals(0, Progression.milestoneCrossed(49, 50, 0));
        assertEquals(0, Progression.milestoneCrossed(50, 50, 50));
        assertEquals(0, Progression.milestoneCrossed(100, 50, 50));
    }

    @Test void levelsAtExactThresholdsAndCarriesExcess() {
        assertEquals(1, curve.progress(9).level());
        assertEquals(2, curve.progress(10).level());
        var progress = curve.progress(10 + curve.required(2) + 3.5);
        assertEquals(3, progress.level());
        assertEquals(3.5, progress.xp());
    }
    @Test void quotasRiseAndEventuallyPlateau() {
        double previous = 0;
        for (int level = 1; level < 10_000; level++) {
            assertTrue(curve.required(level) >= previous);
            assertTrue(curve.required(level) <= 2500);
            previous = curve.required(level);
        }
        assertEquals(10, curve.required(1));
        assertEquals(100, curve.required(10));
        assertEquals(1300, curve.required(30));
        assertEquals(2500, curve.required(50));
        assertEquals(curve.required(50), curve.required(51));
        assertEquals(2500, curve.required(10_000));
    }
    @Test void handlesLargeTotalsAndFlatCurves() {
        var flat = new Progression(10, 10, 10, 2, 3, 1);
        assertEquals(1_000_001, flat.progress(10_000_005).level());
        assertEquals(5, flat.progress(10_000_005).xp());
        assertTrue(curve.progress(1e12).level() > 1_000_000);
    }
    @Test void rejectsInvalidSettingsAndXp() {
        assertThrows(IllegalArgumentException.class, () -> new Progression(0, 100, 2500, 10, 50, 1));
        assertThrows(IllegalArgumentException.class, () -> new Progression(10, 5, 2500, 10, 50, 1));
        assertThrows(IllegalArgumentException.class, () -> new Progression(10, 100, 2500, 10, 50, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new Progression(10, 100, 2500, 50, 10, 1));
        assertThrows(IllegalArgumentException.class, () -> curve.progress(Double.POSITIVE_INFINITY));
    }
    @Test void multipliersChangeEveryQuotaWithoutCompoundingAcrossLevels() {
        var doubled = new Progression(10, 100, 2500, 10, 50, 2);
        assertEquals(20, doubled.required(1));
        assertEquals(200, doubled.required(10));
        assertEquals(5000, doubled.required(50));
        assertEquals(5000, doubled.required(10000));
        assertEquals(1, doubled.progress(10).level());
        assertEquals(2, doubled.progress(20).level());
    }
    @Test void everyLevelBoundaryAndTheFlatSectionKeepExcessXp() {
        double total = 0;
        for (int level = 1; level <= 500; level++) {
            assertEquals(level, curve.progress(total).level());
            assertEquals(level, curve.progress(total + curve.required(level) - .5).level());
            total += curve.required(level);
            assertEquals(level + 1, curve.progress(total).level());
            assertEquals(.25, curve.progress(total + .25).xp());
        }
    }
}
