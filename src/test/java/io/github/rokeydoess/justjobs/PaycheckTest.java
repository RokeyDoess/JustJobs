package io.github.rokeydoess.justjobs;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PaycheckTest {
    static final long HOUR = 3_600_000;
    @Test void bossBonusesPreserveTheNormalAllowanceAndSurvivePartialRefunds() {
        var p = new Paycheck(); var cap = new BigDecimal("25");
        p.earnBonus("hunting", new BigDecimal("50"), 1, HOUR, true);
        assertEquals(0, p.earned("hunting", 1).signum());
        assertEquals(cap, p.earn("hunting", cap, cap, 2, HOUR, true));
        assertEquals(0, p.available().compareTo(new BigDecimal("50")));
        assertEquals(cap, p.snapshot().current().get("hunting"));
        p.advance(HOUR, HOUR, true);
        var reserved = new HashMap<>(p.take(new BigDecimal("75")));
        assertEquals(Map.of("hunting", new BigDecimal("75")), Paycheck.byJob(reserved));
        assertEquals(Map.of("hunting", new BigDecimal("12.5")), Paycheck.byJob(Paycheck.take(reserved, new BigDecimal("12.5"))));
        p.refund(reserved);
        var restored = new Paycheck();
        p.snapshot().due().forEach((job, amount) -> restored.restore(p.snapshot().next(), job, amount, true));
        assertEquals(0, restored.available().compareTo(new BigDecimal("62.5")));
        assertEquals(cap, restored.earn("hunting", cap, cap, HOUR + 1, HOUR, true));
        restored.reschedule(HOUR + 2, HOUR, false);
        assertEquals(0, restored.available().compareTo(new BigDecimal("87.5")));
    }
    @Test void jobCapsResetAtTheCutoffEvenWhenThePreviousPaycheckIsUnpaid() {
        var p = new Paycheck(); var limit = new BigDecimal("100");
        assertEquals(new BigDecimal("99.75"), p.earn("hunting", new BigDecimal("99.75"), limit, HOUR - 2, HOUR, true));
        assertEquals(new BigDecimal("0.25"), p.earn("hunting", BigDecimal.TEN, limit, HOUR - 1, HOUR, true));
        assertEquals(0, p.earn("hunting", BigDecimal.ONE, limit, HOUR - 1, HOUR, true).signum());
        assertEquals(0, p.earned("hunting", HOUR).signum());
        assertEquals(limit, p.earn("hunting", limit, limit, HOUR, HOUR, true));
        assertEquals(0, p.available().compareTo(limit));
        assertEquals(0, p.earned("hunting", HOUR).compareTo(limit));
        var reserved = p.take(limit); p.refund(reserved);
        assertEquals(0, p.earn("hunting", BigDecimal.ONE, limit, HOUR + 1, HOUR, true).signum());
        assertEquals(0, p.available().compareTo(limit));
    }
    @Test void longPayPeriodsKeepTheirCapThroughAnHourAndReload() {
        var p = new Paycheck(); var limit = new BigDecimal("100");
        p.earn("mining", limit, limit, 1, 2 * HOUR, true);
        assertEquals(0, p.earn("mining", BigDecimal.ONE, limit, HOUR + 60_000, 2 * HOUR, true).signum());
        p.reschedule(HOUR + 60_001, 2 * HOUR, true);
        assertEquals(0, p.earn("mining", BigDecimal.ONE, limit, HOUR + 60_002, 2 * HOUR, true).signum());
        assertEquals(limit, p.earn("mining", limit, limit, 2 * HOUR, 2 * HOUR, true));
    }
    @Test void capChangesKeepTheCurrentPeriodAndJobsHaveSeparateAllowances() {
        var p = new Paycheck(); var limit = new BigDecimal("100");
        assertEquals(new BigDecimal("80"), p.earn("mining", new BigDecimal("80"), BigDecimal.ZERO, 1, HOUR, true));
        assertEquals(0, p.earn("mining", BigDecimal.TEN, new BigDecimal("50"), 2, HOUR, true).signum());
        assertEquals(new BigDecimal("20"), p.earn("mining", limit, limit, 3, HOUR, true));
        assertEquals(limit, p.earn("digging", limit, limit, 4, HOUR, true));
        assertEquals(BigDecimal.ONE, p.earn("mining", BigDecimal.ONE, BigDecimal.ZERO, 5, HOUR, true));
        assertEquals(0, p.earn("mining", BigDecimal.ONE, limit, 6, HOUR, true).signum());
    }
    @Test void extendingTheScheduleKeepsTheAlreadyEarnedQuota() {
        var p = new Paycheck(); var limit = new BigDecimal("100");
        p.earn("mining", limit, limit, 1, HOUR, true);
        p.reschedule(HOUR / 2, 2 * HOUR, true);
        assertEquals(0, p.earn("mining", BigDecimal.ONE, limit, HOUR + 1, 2 * HOUR, true).signum());
        assertEquals(limit, p.earn("mining", limit, limit, 2 * HOUR, 2 * HOUR, true));
    }
    @Test void fractionalRatesStopExactlyAtThePeriodLimitWithoutBankingExcess() {
        var p = new Paycheck(); var limit = new BigDecimal("100"); var accepted = BigDecimal.ZERO;
        for (int i = 0; i < 1000; i++) accepted = accepted.add(p.earn("hunting", new BigDecimal("0.1667"), limit, i, HOUR, true));
        assertEquals(0, accepted.compareTo(limit));
        assertEquals(0, p.earn("hunting", BigDecimal.ONE, limit, HOUR, HOUR, true).compareTo(BigDecimal.ONE));
        assertEquals(0, p.earned("hunting", HOUR).compareTo(BigDecimal.ONE));
    }
    @Test void periodsUseSharedClockBoundariesAndNeverPayNewEarningsEarly() {
        var p = new Paycheck();
        p.earn("mining", new BigDecimal("8.75"), HOUR + 1, HOUR, true);
        p.earn("fishing", new BigDecimal("1.50"), HOUR + 2, HOUR, true);
        assertEquals(2 * HOUR, p.snapshot().next());
        assertEquals(0, p.available().signum());
        p.earn("mining", new BigDecimal("20"), 2 * HOUR, HOUR, true);
        assertEquals(new BigDecimal("10.25"), p.available());
        var paid = p.take(BigDecimal.TEN);
        assertEquals(0, Paycheck.total(paid).compareTo(BigDecimal.TEN));
        assertEquals(new BigDecimal("0.25"), p.available());
        assertEquals(new BigDecimal("20"), p.snapshot().current().get("mining"));
        assertThrows(IllegalArgumentException.class, () -> p.take(BigDecimal.ONE));
    }
    @Test void partialCreditsRefundOnlyTheUnpaidReleasedJobs() {
        var p = new Paycheck(); p.earn("digging", new BigDecimal("5"), 0, HOUR, true);
        p.advance(HOUR, HOUR, true);
        var reserved = new HashMap<>(p.take(new BigDecimal("5")));
        var credited = Paycheck.take(reserved, new BigDecimal("2"));
        p.earn("fishing", BigDecimal.ONE, HOUR + 1, HOUR, true); p.refund(reserved);
        assertEquals(new BigDecimal("2"), credited.get("digging"));
        assertEquals(new BigDecimal("3"), p.available());
        assertEquals(BigDecimal.ONE, p.snapshot().current().get("fishing"));
    }
    @Test void downtimeReleasesMissedPeriodsOnlyOnce() {
        var p = new Paycheck(); p.earn("mining", BigDecimal.TEN, 1, HOUR, true);
        assertTrue(p.advance(5 * HOUR + 1, HOUR, true));
        assertEquals(BigDecimal.TEN, p.available());
        assertEquals(6 * HOUR, p.snapshot().next());
        assertFalse(p.advance(5 * HOUR + 2, HOUR, true));
        p.take(BigDecimal.TEN); assertEquals(0, p.available().signum());
    }
    @Test void changingModeOrIntervalPreservesEarnings() {
        var p = new Paycheck(); p.earn("mining", BigDecimal.TEN, 1, HOUR, true);
        p.reschedule(100, 2 * HOUR, true);
        assertEquals(2 * HOUR, p.snapshot().next()); assertEquals(0, p.available().signum());
        p.reschedule(101, HOUR, false);
        assertEquals(BigDecimal.TEN, p.available());
        p.reschedule(102, HOUR, true);
        assertEquals(BigDecimal.TEN, p.available());
    }
    @Test void legacyBalancesRemainPayableAndMismatchCannotHideMissingMoney() {
        var p = new Paycheck(); p.reconcile(new BigDecimal("0.37"));
        assertEquals(new BigDecimal("0.37"), p.available());
        p.reconcile(new BigDecimal("0.37"));
        assertEquals(1, p.snapshot().due().size());
        assertThrows(IllegalStateException.class, () -> p.reconcile(BigDecimal.ZERO));
    }
    @Test void restoredLedgerPreservesBothPeriods() {
        var p = new Paycheck();
        p.restore(HOUR, "fishing", new BigDecimal("0.25"), true);
        p.restore(HOUR, "mining", BigDecimal.ONE, false);
        p.reconcile(new BigDecimal("1.25"));
        assertEquals(Map.of("fishing", new BigDecimal("0.25")), p.snapshot().due());
        assertEquals(0, p.take(new BigDecimal("0.25")).get("fishing").compareTo(new BigDecimal("0.25")));
        assertEquals(BigDecimal.ONE, p.snapshot().current().get("mining"));
    }
}
