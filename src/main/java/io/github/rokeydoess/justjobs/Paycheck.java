package io.github.rokeydoess.justjobs;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/** Separates the current period from money already released for payment. */
public final class Paycheck {
    public static final String PREVIOUS = "@previous";
    private static final String BONUS = "@bonus:";
    private static final String CARRY = "@carry:";
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
    public BigDecimal available(boolean bonus) {
        BigDecimal amount = BigDecimal.ZERO;
        for (var entry : due.entrySet()) if (isBonus(entry.getKey()) == bonus) amount = amount.add(entry.getValue());
        return amount;
    }
    public BigDecimal earnBonus(String job, BigDecimal amount, long now, long interval, boolean enabled) {
        return earnBonus(job, job, amount, now, interval, enabled);
    }
    public BigDecimal earnBonus(String job, String source, BigDecimal amount, long now, long interval, boolean enabled) {
        return earnBonus(job, source, amount, now, interval, enabled, false);
    }
    public BigDecimal earnBonus(String job, String source, BigDecimal amount, long now, long interval, boolean enabled, boolean whole) {
        if (amount.signum() < 0) throw new IllegalArgumentException("Negative bonus amount");
        advance(now, interval, enabled);
        BigDecimal immediate = whole ? amount.setScale(0, java.math.RoundingMode.DOWN) : amount;
        if (immediate.signum() > 0) due.merge(BONUS + job + ":" + source, immediate, BigDecimal::add);
        carry(job, amount.subtract(immediate), enabled);
        return amount;
    }
    /** Older balances and partial provider credits may leave fractions in the bonus bucket. */
    public boolean deferBonusRemainders(boolean payday) {
        Map<String, BigDecimal> remainders = null;
        for (var iterator = due.entrySet().iterator(); iterator.hasNext();) {
            var entry = iterator.next();
            if (!isBonus(entry.getKey())) continue;
            BigDecimal whole = entry.getValue().setScale(0, java.math.RoundingMode.DOWN);
            BigDecimal fraction = entry.getValue().subtract(whole);
            if (fraction.signum() == 0) continue;
            if (remainders == null) remainders = new HashMap<>();
            remainders.merge(jobId(entry.getKey()), fraction, BigDecimal::add);
            if (whole.signum() == 0) iterator.remove(); else entry.setValue(whole);
        }
        if (remainders == null) return false;
        remainders.forEach((job, amount) -> carry(job, amount, payday));
        return true;
    }
    private void carry(String job, BigDecimal amount, boolean payday) {
        // A separate key preserves the source job without consuming its normal allowance.
        if (amount.signum() > 0) (payday ? current : due).merge(CARRY + job, amount, BigDecimal::add);
    }
    public static boolean isBonus(String key) { return key.startsWith(BONUS); }
    private static String jobId(String key) {
        if (key.startsWith(CARRY)) return key.substring(CARRY.length());
        if (!isBonus(key)) return key;
        String value = key.substring(BONUS.length()); int separator = value.indexOf(':');
        return separator < 0 ? value : value.substring(0, separator);
    }
    public static Map<String, BigDecimal> bonusSources(Map<String, BigDecimal> amounts) {
        var result = new TreeMap<String, BigDecimal>();
        amounts.forEach((key, value) -> {
            if (!isBonus(key)) return;
            int separator = key.indexOf(':', BONUS.length());
            result.merge(separator < 0 ? jobId(key) : key.substring(separator + 1), value, BigDecimal::add);
        });
        return Map.copyOf(result);
    }
    /** Preserve bonus attribution through reservations and refunds without using the normal cap bucket. */
    public static Map<String, BigDecimal> byJob(Map<String, BigDecimal> amounts) {
        var result = new TreeMap<String, BigDecimal>();
        amounts.forEach((job, value) -> result.merge(jobId(job), value, BigDecimal::add));
        return Map.copyOf(result);
    }
    public Map<String, BigDecimal> take(BigDecimal amount) { return take(due, amount); }
    public Map<String, BigDecimal> take(BigDecimal amount, boolean bonus) {
        var eligible = new HashMap<String, BigDecimal>();
        due.forEach((job, value) -> { if (isBonus(job) == bonus) eligible.put(job, value); });
        var taken = take(eligible, amount);
        taken.forEach((job, value) -> {
            BigDecimal rest = due.get(job).subtract(value);
            if (rest.signum() == 0) due.remove(job); else due.put(job, rest);
        });
        return taken;
    }
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
