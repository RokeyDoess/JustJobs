package io.github.rokeydoess.justjobs;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

final class LevelUpMessages {
    record Notice(String job, String name, int level) { }
    private static final class State {
        final Map<String, Notice> pending = new LinkedHashMap<>();
        Long lastSent;
    }
    private final Map<UUID, State> states = new HashMap<>();

    void offer(UUID player, Notice notice) {
        var state = states.computeIfAbsent(player, ignored -> new State());
        state.pending.merge(notice.job(), notice, (old, latest) -> latest.level() >= old.level() ? latest : old);
    }

    void flush(UUID player, long now, long intervalMillis, Consumer<Notice> send) {
        var state = states.get(player);
        if (state == null || state.lastSent != null && intervalMillis > 0 && now - state.lastSent < intervalMillis) return;
        while (!state.pending.isEmpty()) {
            var first = state.pending.entrySet().iterator();
            var notice = first.next().getValue(); first.remove();
            state.lastSent = now; send.accept(notice);
            if (intervalMillis > 0) break;
        }
    }

    Set<UUID> pendingPlayers() {
        var result = new java.util.HashSet<UUID>();
        states.forEach((id, state) -> { if (!state.pending.isEmpty()) result.add(id); });
        return result;
    }
    void forget(UUID player) { states.remove(player); }
    void clear() { states.clear(); }
}
