package io.github.rokeydoess.justjobs;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LevelBonusesTest {
    private final BigDecimal half = new BigDecimal("0.5");

    @Test void milestonesPayHalfTheReachedLevelOnlyOnce() {
        var bonuses = new LevelBonuses();
        assertEquals(new BigDecimal("25.0"), bonuses.claim("mining", 49, 50, 50, half).amount());
        assertNull(bonuses.claim("mining", 50, 51, 50, half));
        assertEquals(new BigDecimal("50.0"), bonuses.claim("mining", 99, 100, 50, half).amount());
        assertEquals(new BigDecimal("25.0"), bonuses.claim("cooking", 49, 50, 50, half).amount());
    }

    @Test void bulkGainsIncludeEveryCrossedMilestone() {
        var reward = new LevelBonuses().claim("mining", 49, 151, 50, half);
        assertEquals(50, reward.first());
        assertEquals(150, reward.last());
        assertEquals(new BigDecimal("150.0"), reward.amount());
    }

    @Test void existingLevelsAndProgressionChangesDoNotReplayBonuses() {
        var bonuses = new LevelBonuses();
        assertNull(bonuses.claim("mining", 225, 226, 50, half));
        assertNull(bonuses.claim("mining", 49, 100, 50, half));
        assertEquals(new BigDecimal("125.0"), bonuses.claim("mining", 249, 250, 50, half).amount());
        assertNull(bonuses.claim("mining", 500, 501, 50, half));
        assertNull(bonuses.claim("mining", 499, 500, 50, half));
    }

    @Test void disabledPeriodsAndZeroRewardsStillAdvanceClaimProgress() {
        var bonuses = new LevelBonuses();
        assertNull(bonuses.claim("mining", 49, 100, 0, half));
        assertNull(bonuses.claim("mining", 49, 100, 50, half));
        assertNull(bonuses.claim("mining", 149, 150, 50, BigDecimal.ZERO));
        assertNull(bonuses.claim("mining", 149, 150, 50, half));
        assertEquals(new BigDecimal("100.0"), bonuses.claim("mining", 199, 200, 50, half).amount());
    }

    @Test void customIntervalsAndRatesPreserveFractions() {
        var reward = new LevelBonuses().claim("building", 19, 61, 20, new BigDecimal("0.025"));
        assertEquals(new BigDecimal("3.000"), reward.amount());
    }

    @Test void enormousLevelGainsUseExactArithmeticWithoutIteratingThroughLevels() {
        var reward = new LevelBonuses().claim("mining", 1, Integer.MAX_VALUE, 1, half);
        var last = BigDecimal.valueOf(Integer.MAX_VALUE);
        var expected = last.multiply(last.add(BigDecimal.ONE)).divide(BigDecimal.valueOf(2)).subtract(BigDecimal.ONE).multiply(half);
        assertEquals(0, reward.amount().compareTo(expected));
        assertEquals(Integer.MAX_VALUE, reward.last());
    }
}
