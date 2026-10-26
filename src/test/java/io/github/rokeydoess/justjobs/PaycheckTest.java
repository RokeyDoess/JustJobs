package io.github.rokeydoess.justjobs;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PaycheckTest {
    static final long HOUR = 3_600_000;
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
