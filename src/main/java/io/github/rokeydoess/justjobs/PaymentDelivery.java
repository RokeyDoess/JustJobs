package io.github.rokeydoess.justjobs;

import java.math.BigDecimal;

/** Checks a synchronous Vault credit on the server thread before confirming the paid amount. */
final class PaymentDelivery {
    record Result(BigDecimal credited, BigDecimal before, boolean complete) { }
    interface Account {
        BigDecimal balance();
        boolean deposit(BigDecimal amount);
    }
    static Result deliver(Account account, BigDecimal amount) {
        BigDecimal before = balance(account);
        // Don't start a payment which cannot be checked afterward.
        if (before == null) return new Result(BigDecimal.ZERO, null, true);
        try { account.deposit(amount); }
        catch (RuntimeException ignored) { /* Some providers throw after applying the credit. */ }
        return reconcile(account, amount, before);
    }
    static Result reconcile(Account account, BigDecimal amount, BigDecimal before) {
        BigDecimal after = balance(account);
        if (after == null) return new Result(BigDecimal.ZERO, before, false);
        BigDecimal delta = after.subtract(before).max(BigDecimal.ZERO).min(amount);
        // Vault uses doubles. Ignore their tiny representation error, never an unpaid amount.
        BigDecimal tolerance = amount.multiply(new BigDecimal("0.000000001")).min(new BigDecimal("0.000000001"));
        BigDecimal credited = delta.signum() > 0 && delta.add(tolerance).compareTo(amount) >= 0
                ? amount : delta.setScale(8, java.math.RoundingMode.DOWN).stripTrailingZeros();
        return new Result(credited, before, true);
    }
    private static BigDecimal balance(Account account) {
        try { return account.balance(); }
        catch (RuntimeException ignored) { return null; }
    }
}
