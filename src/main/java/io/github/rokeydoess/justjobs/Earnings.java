package io.github.rokeydoess.justjobs;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/** A conservative rolling hour: a minute is released only once all of it is an hour old. */
public final class Earnings {
    private final Map<String, Window> jobs = new HashMap<>();
    private static final class Window {
        final TreeMap<Long, BigDecimal> minutes = new TreeMap<>();
        BigDecimal total = BigDecimal.ZERO;
        void prune(long minute) {
            while (!minutes.isEmpty() && minutes.firstKey() < minute - 60)
                total = total.subtract(minutes.pollFirstEntry().getValue());
        }
    }

    public BigDecimal credit(String job, BigDecimal requested, BigDecimal limit, long now) {
        var window = jobs.computeIfAbsent(job, ignored -> new Window());
        long minute = Math.floorDiv(now, 60_000);
        window.prune(minute);
        BigDecimal accepted = limit.signum() == 0 ? requested : requested.min(limit.subtract(window.total).max(BigDecimal.ZERO));
        if (accepted.signum() > 0) {
            window.minutes.merge(minute, accepted, BigDecimal::add);
            window.total = window.total.add(accepted);
        }
        return accepted;
    }

    public BigDecimal total(String job, long now) {
        var window = jobs.get(job);
        if (window == null) return BigDecimal.ZERO;
        window.prune(Math.floorDiv(now, 60_000));
        return window.total;
    }

    public Map<String, Map<Long, BigDecimal>> snapshot(long now) {
        var result = new HashMap<String, Map<Long, BigDecimal>>();
        long minute = Math.floorDiv(now, 60_000);
        jobs.forEach((job, window) -> {
            window.prune(minute);
            if (!window.minutes.isEmpty()) result.put(job, Map.copyOf(window.minutes));
        });
        return Map.copyOf(result);
    }

    public void restore(String job, long minute, BigDecimal amount) {
        if (amount.signum() <= 0) throw new IllegalArgumentException("Saved earnings must be positive");
        var window = jobs.computeIfAbsent(job, ignored -> new Window());
        BigDecimal previous = window.minutes.put(minute, amount);
        window.total = window.total.add(amount).subtract(previous == null ? BigDecimal.ZERO : previous);
    }
}
