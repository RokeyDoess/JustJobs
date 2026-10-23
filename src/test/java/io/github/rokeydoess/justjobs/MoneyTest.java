package io.github.rokeydoess.justjobs;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MoneyTest {
    @Test void dirtAccumulatesPastAWholeCoinWithoutLosingSubCentRemainders() {
        BigDecimal pending = BigDecimal.ZERO;
        for (int i = 0; i < 133; i++) pending = pending.add(new BigDecimal("0.0075"));
        assertEquals(BigDecimal.ZERO, Money.payable(pending, true, 2));
        pending = pending.add(new BigDecimal("0.0075"));
        assertEquals(BigDecimal.ONE, Money.payable(pending, true, 2));
        assertEquals(new BigDecimal("0.0050"), pending.subtract(Money.payable(pending, true, 2)));
    }
    @Test void preservesRemaindersRatherThanRoundingUp() {
        var pending = new BigDecimal("1.379");
        assertEquals(new BigDecimal("1"), Money.payable(pending, true, 2));
        assertEquals(new BigDecimal("0.379"), pending.subtract(Money.payable(pending, true, 2)));
        assertEquals(new BigDecimal("1.37"), Money.payable(pending, false, 2));
        assertEquals(new BigDecimal("1"), Money.payable(pending, false, 0));
    }
    @Test void smallTravelPaymentsAccumulateWithoutDrift() {
        var pending = new BigDecimal("0.002").multiply(new BigDecimal("500"));
        assertEquals(BigDecimal.ONE, Money.payable(pending, true, -1));
    }
    @Test void unlimitedPrecisionProvidersDoNotWaitForWholeCentsInFractionalMode() {
        assertEquals(new BigDecimal("0.00200000"), Money.payable(new BigDecimal("0.002"), false, -1));
        assertEquals(new BigDecimal("0.00000001"), Money.payable(new BigDecimal("0.000000019"), false, -1));
        assertEquals(new BigDecimal("0.00"), Money.payable(new BigDecimal("0.002"), false, 2));
    }
}
