package io.github.rokeydoess.justjobs;

import java.util.HashMap;
import java.util.Map;

/** Distances below the next reward stay with the player, including across relogs. */
public final class TravelProgress {
    private final Map<String, Double> distances = new HashMap<>();
    public double add(String job, double distance, double interval) {
        if (!Double.isFinite(distance) || distance < 0 || !Double.isFinite(interval) || interval < 1)
            throw new IllegalArgumentException("Travel distance must be nonnegative and reward interval at least one block");
        double total = distance(job) + distance;
        double completed = Math.floor((total + 1e-9) / interval) * interval;
        restore(job, Math.max(0, total - completed));
        return completed;
    }
    public double distance(String job) { return distances.getOrDefault(job, 0.0); }
    public void restore(String job, double distance) {
        if (!Double.isFinite(distance) || distance < 0) throw new IllegalArgumentException("Invalid saved travel distance");
        if (distance == 0) distances.remove(job); else distances.put(job, distance);
    }
    public Map<String, Double> snapshot() { return Map.copyOf(distances); }
}
