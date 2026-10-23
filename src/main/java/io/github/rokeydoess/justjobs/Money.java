package io.github.rokeydoess.justjobs;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class Money {
    private Money() { }

    public static BigDecimal payable(BigDecimal pending, boolean whole, int providerDigits) {
        int scale = whole ? 0 : providerDigits < 0 ? 8 : Math.min(providerDigits, 8);
        return pending.setScale(scale, RoundingMode.DOWN);
    }
}
