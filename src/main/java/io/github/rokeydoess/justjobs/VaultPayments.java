package io.github.rokeydoess.justjobs;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;

import java.math.BigDecimal;

final class VaultPayments {
    private final JustJobsPlugin plugin;
    private record Pending(Economy provider, BigDecimal before) { }
    private final java.util.Map<String, Pending> uncertain = new java.util.HashMap<>();
    VaultPayments(JustJobsPlugin plugin) { this.plugin = plugin; }
    private Economy provider() {
        var registration = Bukkit.getServicesManager().getRegistration(Economy.class);
        return registration == null ? null : registration.getProvider();
    }
    boolean available() {
        try { var economy = provider(); return economy != null && economy.isEnabled(); }
        catch (RuntimeException e) { return false; }
    }
    BigDecimal payable(BigDecimal pending) {
        try {
            var economy = provider();
            if (economy == null || !economy.isEnabled()) return BigDecimal.ZERO;
            return Money.payable(pending, plugin.settings().getBoolean("economy.whole-number-payouts", true), economy.fractionalDigits());
        } catch (RuntimeException e) { return BigDecimal.ZERO; }
    }
    BigDecimal preview(BigDecimal pending) {
        int digits = -1;
        try {
            var economy = provider();
            if (economy != null && economy.isEnabled()) digits = economy.fractionalDigits();
        } catch (RuntimeException ignored) { }
        return Money.payable(pending, plugin.settings().getBoolean("economy.whole-number-payouts", true), digits);
    }
    PaymentDelivery.Result deposit(DataStore.Payment payment) {
        try {
            var economy = provider();
            var pending = uncertain.get(payment.id());
            if (economy == null || !economy.isEnabled()) return new PaymentDelivery.Result(BigDecimal.ZERO, null, pending == null);
            if (pending != null && pending.provider() != economy) return new PaymentDelivery.Result(BigDecimal.ZERO, pending.before(), false);
            var player = Bukkit.getOfflinePlayer(payment.player());
            if (pending == null && !economy.hasAccount(player) && !economy.createPlayerAccount(player))
                return new PaymentDelivery.Result(BigDecimal.ZERO, null, true);
            var account = new PaymentDelivery.Account() {
                public BigDecimal balance() { return BigDecimal.valueOf(economy.getBalance(player)); }
                public boolean deposit(BigDecimal amount) {
                    var result = economy.depositPlayer(player, amount.doubleValue());
                    return result != null && result.transactionSuccess();
                }
            };
            var result = pending == null ? PaymentDelivery.deliver(account, payment.amount())
                    : PaymentDelivery.reconcile(account, payment.amount(), pending.before());
            if (result.complete()) uncertain.remove(payment.id());
            else uncertain.put(payment.id(), new Pending(economy, result.before()));
            return result;
        } catch (RuntimeException e) { return new PaymentDelivery.Result(BigDecimal.ZERO, null, !uncertain.containsKey(payment.id())); }
    }
}
