package io.github.rokeydoess.justjobs;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class PlayerProfile {
    public final UUID id;
    public String name;
    public boolean notifications = true;
    public boolean bossBar = true;
    public BigDecimal pending = BigDecimal.ZERO;
    public final Map<String, Double> xp = new HashMap<>();
    public final Earnings earnings = new Earnings();
    public final TravelProgress travel = new TravelProgress();

    public PlayerProfile(UUID id, String name) { this.id = id; this.name = name; }
    public double xp(String job) { return xp.getOrDefault(job, 0.0); }
    public Snapshot snapshot() { return snapshot(System.currentTimeMillis()); }
    public Snapshot snapshot(long now) { return new Snapshot(id, name, notifications, bossBar, pending, Map.copyOf(xp), earnings.snapshot(now), travel.snapshot()); }
    public record Snapshot(UUID id, String name, boolean notifications, boolean bossBar, BigDecimal pending,
                           Map<String, Double> xp, Map<String, Map<Long, BigDecimal>> earnings, Map<String, Double> travel) { }
}
