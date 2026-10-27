package io.github.rokeydoess.justjobs;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/** Separates the current period from money already released for payment. */
public final class Paycheck {
    public static final String PREVIOUS = "@previous";
    private final Map<String, BigDecimal> current = new HashMap<>(), due = new HashMap<>();
    private long next;
    public record Snapshot(long next, Map<String, BigDecimal> current, Map<String, BigDecimal> due) { }

    public static long next(long now, long interval) {
        if (interval <= 0) throw new IllegalArgumentException("Payday interval must be positive");
        return Math.multiplyExact(Math.floorDiv(now, interval) + 1, interval);
    }
    public boolean advance(long now, long interval, boolean enabled) {
        boolean changed = false;
        if (!enabled || next > 0 && now >= next) {
            if (!current.isEmpty()) { add(due, current); current.clear(); changed = true; }
        }
        if (next == 0 || now >= next || !enabled) {
            long scheduled = enabled ? next(now, interval) : 0;
            changed |= next != scheduled; next = scheduled;
        }
        return changed;
    }
    public void reschedule(long now, long interval, boolean enabled) {
        advance(now, interval, enabled);
        next = enabled ? next(now, interval) : 0;
    }
    public void earn(String job, BigDecimal amount, long now, long interval, boolean enabled) {
        earn(job, amount, BigDecimal.ZERO, now, interval, enabled);
    }
    public BigDecimal earn(String job, BigDecimal amount, BigDecimal limit, long now, long interval, boolean enabled) {
        advance(now, interval, enabled);
        BigDecimal accepted = enabled && limit.signum() > 0
                ? amount.min(limit.subtract(current.getOrDefault(job, BigDecimal.ZERO)).max(BigDecimal.ZERO)) : amount;
        if (accepted.signum() > 0) (enabled ? current : due).merge(job, accepted, BigDecimal::add);
        return accepted;
    }
    public BigDecimal earned(String job, long now) {
        return next > 0 && now >= next ? BigDecimal.ZERO : current.getOrDefault(job, BigDecimal.ZERO);
    }
    public BigDecimal available() { return total(due); }
    public Map<String, BigDecimal> take(BigDecimal amount) { return take(due, amount); }
    public void refund(Map<String, BigDecimal> amounts) { add(due, amounts); }
    public Snapshot snapshot() { return new Snapshot(next, Map.copyOf(current), Map.copyOf(due)); }
    public void restore(long next, String job, BigDecimal amount, boolean released) {
        if (next < 0 || amount.signum() < 0) throw new IllegalArgumentException("Invalid saved paycheck");
        this.next = next;
        if (amount.signum() > 0) (released ? due : current).put(job, amount);
    }
    public void restoreNext(long next) {
        if (next < 0) throw new IllegalArgumentException("Invalid payday timestamp");
        this.next = next;
    }
    public void reconcile(BigDecimal pending) {
        BigDecimal missing = pending.subtract(total(current)).subtract(total(due));
        if (missing.signum() < 0) throw new IllegalStateException("Paycheck exceeds unpaid earnings");
        if (missing.signum() > 0) due.merge(PREVIOUS, missing, BigDecimal::add);
    }
    public static BigDecimal total(Map<String, BigDecimal> amounts) {
        return amounts.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }
    public static Map<String, BigDecimal> take(Map<String, BigDecimal> source, BigDecimal amount) {
        if (amount.signum() < 0 || amount.compareTo(total(source)) > 0) throw new IllegalArgumentException("Payment exceeds released earnings");
        var result = new TreeMap<String, BigDecimal>();
        BigDecimal remaining = amount;
        for (String job : new TreeMap<>(source).keySet()) {
            BigDecimal part = source.get(job).min(remaining);
            if (part.signum() > 0) { result.put(job, part); remaining = remaining.subtract(part); }
            BigDecimal rest = source.get(job).subtract(part);
            if (rest.signum() == 0) source.remove(job); else source.put(job, rest);
            if (remaining.signum() == 0) break;
        }
        return Map.copyOf(result);
    }
    private static void add(Map<String, BigDecimal> target, Map<String, BigDecimal> amounts) {
        amounts.forEach((job, value) -> {
            if (value.signum() < 0) throw new IllegalArgumentException("Negative paycheck amount");
            if (value.signum() > 0) target.merge(job, value, BigDecimal::add);
        });
    }
}
