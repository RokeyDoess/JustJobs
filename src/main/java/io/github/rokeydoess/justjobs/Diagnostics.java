package io.github.rokeydoess.justjobs;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/** Optional windowed summaries; no per-action log writes. */
final class Diagnostics {
    private record Key(UUID player, String name, String job) { }
    private static final class Stats {
        long actions;
        double xp;
        BigDecimal earned = BigDecimal.ZERO, capped = BigDecimal.ZERO, paid = BigDecimal.ZERO;
        void add(Stats other) { actions += other.actions; xp += other.xp; earned = earned.add(other.earned); capped = capped.add(other.capped); paid = paid.add(other.paid); }
    }
    private final Logger logger;
    private final Map<Key, Stats> rows = new HashMap<>();
    private boolean enabled;
    private long started, interval;
    private int payments, failures, partial;
    Diagnostics(Logger logger) { this.logger = logger; }
    void configure(boolean enabled, long interval, long now) {
        if (this.enabled) flush(now);
        this.enabled = enabled; this.interval = interval; started = now;
    }
    void award(PlayerProfile player, String job, double xp, BigDecimal gross, BigDecimal credited) {
        if (!enabled) return;
        var stats = rows.computeIfAbsent(new Key(player.id, player.name, job), ignored -> new Stats());
        stats.actions++; stats.xp += xp; stats.earned = stats.earned.add(credited); stats.capped = stats.capped.add(gross.subtract(credited));
    }
    void payment(DataStore.Payment payment, BigDecimal credited, Map<String, BigDecimal> jobs) {
        if (!enabled) return;
        payments++;
        if (credited.signum() == 0) failures++;
        else if (credited.compareTo(payment.amount()) < 0) partial++;
        jobs.forEach((job, amount) -> {
            var stats = rows.computeIfAbsent(new Key(payment.player(), payment.name(), job), ignored -> new Stats());
            stats.paid = stats.paid.add(amount);
        });
    }
    void tick(long now) { if (enabled && now - started >= interval) flush(now); }
    void flush(long now) {
        if (!enabled) return;
        long elapsed = Math.max(1, now - started);
        var totals = new java.util.TreeMap<String, Stats>();
        rows.forEach((key, stats) -> totals.computeIfAbsent(key.job(), ignored -> new Stats()).add(stats));
        logger.info("[Stats] Window " + elapsed / 1000 + "s; player/job rows=" + rows.size() + "; payments=" + payments + "; rejected=" + failures + "; partial=" + partial);
        totals.forEach((job, stats) -> logger.info("[Stats] Job " + job + ": " + describe(stats, elapsed)));
        rows.entrySet().stream().sorted((a, b) -> {
            int amount = b.getValue().earned.compareTo(a.getValue().earned);
            return amount != 0 ? amount : a.getKey().toString().compareTo(b.getKey().toString());
        }).limit(20).forEach(entry -> logger.info("[Stats] Player " + entry.getKey().name() + " (" + entry.getKey().player() + ") / " + entry.getKey().job() + ": " + describe(entry.getValue(), elapsed)));
        if (rows.size() > 20) logger.info("[Stats] Showing the 20 highest-earning player/job rows; job totals include every row.");
        rows.clear(); payments = failures = partial = 0; started = now;
    }
    private static String describe(Stats s, long elapsed) {
        return "actions=" + s.actions + ", xp=" + JustJobsPlugin.format(s.xp) + ", earned=" + s.earned.toPlainString()
                + ", capped=" + s.capped.toPlainString() + ", paid=" + s.paid.toPlainString()
                + ", earned/observed-hour=" + s.earned.multiply(BigDecimal.valueOf(3_600_000)).divide(BigDecimal.valueOf(elapsed), 2, RoundingMode.HALF_UP);
    }
}
