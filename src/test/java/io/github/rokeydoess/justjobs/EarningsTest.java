package io.github.rokeydoess.justjobs;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EarningsTest {
    private static final BigDecimal LIMIT = new BigDecimal("100");
    @Test void clampsTheLastRewardWithoutRoundingOrBankingTheExcess() {
        var earnings = new Earnings();
        assertEquals(new BigDecimal("99.75"), earnings.credit("mining", new BigDecimal("99.75"), LIMIT, 0));
        assertEquals(new BigDecimal("0.25"), earnings.credit("mining", BigDecimal.ONE, LIMIT, 1));
        assertEquals(0, earnings.credit("mining", BigDecimal.TEN, LIMIT, 2).signum());
        assertEquals(0, earnings.total("mining", 2).compareTo(LIMIT));
    }
    @Test void jobsAndPlayersHaveIndependentAllowances() {
        var first = new Earnings(); var second = new Earnings();
        first.credit("hunting", LIMIT, LIMIT, 0);
        assertEquals(LIMIT, first.credit("mining", LIMIT, LIMIT, 0));
        assertEquals(LIMIT, second.credit("hunting", LIMIT, LIMIT, 0));
    }
    @Test void minuteBucketsNeverReleaseMoneyBeforeItIsAnHourOld() {
        var earnings = new Earnings();
        earnings.credit("hunting", LIMIT, LIMIT, 59_999);
        assertEquals(0, earnings.credit("hunting", LIMIT, LIMIT, 3_659_998).signum());
        assertEquals(LIMIT, earnings.credit("hunting", LIMIT, LIMIT, 3_660_000));
    }
    @Test void allowanceReturnsGraduallyInsteadOfResettingAtTheClockHour() {
        var earnings = new Earnings();
        earnings.credit("digging", new BigDecimal("40"), LIMIT, 0);
        earnings.credit("digging", new BigDecimal("60"), LIMIT, 600_000);
        assertEquals(new BigDecimal("40"), earnings.credit("digging", LIMIT, LIMIT, 3_660_000));
        assertEquals(0, earnings.credit("digging", LIMIT, LIMIT, 3_660_001).signum());
    }
    @Test void loweringRaisingAndDisablingTheCapRetainsTheHistory() {
        var earnings = new Earnings();
        earnings.credit("digging", new BigDecimal("80"), BigDecimal.ZERO, 0);
        assertEquals(0, earnings.credit("digging", BigDecimal.TEN, new BigDecimal("50"), 1).signum());
        assertEquals(new BigDecimal("20"), earnings.credit("digging", LIMIT, LIMIT, 2));
        assertEquals(BigDecimal.TEN, earnings.credit("digging", BigDecimal.TEN, BigDecimal.ZERO, 3));
        assertEquals(0, earnings.credit("digging", BigDecimal.ONE, LIMIT, 4).signum());
    }
    @Test void savedWindowsRestoreWithoutResettingTheCap() {
        var earnings = new Earnings(); earnings.credit("digging", LIMIT, LIMIT, 0);
        var restored = new Earnings();
        earnings.snapshot(0).forEach((job, minutes) -> minutes.forEach((minute, amount) -> restored.restore(job, minute, amount)));
        assertEquals(0, restored.credit("digging", LIMIT, LIMIT, 1).signum());
        assertEquals(LIMIT, restored.credit("digging", LIMIT, LIMIT, 3_660_000));
    }
    @Test void thousandsOfFractionalRewardsReachExactlyTheCap() {
        var earnings = new Earnings();
        BigDecimal accepted = BigDecimal.ZERO;
        for (int i = 0; i < 100_000; i++) accepted = accepted.add(earnings.credit("digging", new BigDecimal("0.04"), LIMIT, i));
        assertEquals(0, accepted.compareTo(LIMIT));
        assertEquals(0, earnings.total("digging", 100_000).compareTo(LIMIT));
    }
}
