package io.github.rokeydoess.justjobs;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

final class BossBarCooldowns {
    private final Map<UUID, Map<String, Long>> shown = new HashMap<>();

    boolean acquire(UUID player, String job, long now, long cooldownMillis) {
        var jobs = shown.computeIfAbsent(player, ignored -> new HashMap<>());
        Long last = jobs.get(job);
        if (last != null && cooldownMillis > 0 && now - last < cooldownMillis) return false;
        jobs.put(job, now);
        return true;
    }

    void forget(UUID player) { shown.remove(player); }
}
