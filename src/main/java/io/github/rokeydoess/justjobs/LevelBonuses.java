package io.github.rokeydoess.justjobs;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/** Tracks the highest reached job levels so milestone bonuses cannot be earned again. */
public final class LevelBonuses {
    private final Map<String, Integer> levels = new HashMap<>();
    public record Reward(int first, int last, BigDecimal amount) { }

    public Reward claim(String job, int before, int after, int interval, BigDecimal perLevel) {
        Integer recorded = levels.get(job);
        int previous = recorded == null ? before : Math.max(before, recorded);
        int reached = Math.max(previous, after);
        if (recorded == null || reached > recorded) levels.put(job, reached);
        if (interval == 0 || after <= previous || perLevel.signum() == 0) return null;
        long first = (previous / (long) interval + 1) * interval;
        int last = after - after % interval;
        if (first > last) return null;
        long count = (last - first) / interval + 1;
        BigDecimal sum = BigDecimal.valueOf(first + last).multiply(BigDecimal.valueOf(count)).divide(BigDecimal.TWO);
        return new Reward((int) first, last, sum.multiply(perLevel));
    }

    public Map<String, Integer> snapshot() { return Map.copyOf(levels); }
    public void restore(String job, int level) {
        if (level < 1) throw new IllegalArgumentException("Invalid saved level bonus progress");
        levels.merge(job, level, Math::max);
    }
}
