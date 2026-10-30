package io.github.rokeydoess.justjobs;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Inputs cook in insertion order. Withdrawals remove the most recently added inputs first. */
final class InputBatch {
    record Part(UUID owner, int amount, boolean fallbackAllowed) {
        Part(UUID owner, int amount) { this(owner, amount, owner == null); }
    }
    private final List<Part> parts = new ArrayList<>();
    List<Part> parts() { return List.copyOf(parts); }
    int size() { return parts.stream().mapToInt(Part::amount).sum(); }
    void add(UUID owner, int amount) {
        add(owner, amount, true);
    }
    void add(UUID owner, int amount, boolean fallbackAllowed) {
        if (amount <= 0) return;
        fallbackAllowed &= owner == null;
        if (!parts.isEmpty() && java.util.Objects.equals(parts.getLast().owner(), owner) && parts.getLast().fallbackAllowed() == fallbackAllowed) {
            var last = parts.removeLast(); amount += last.amount();
        }
        parts.add(new Part(owner, amount, fallbackAllowed));
    }
    void resize(int amount, UUID addedOwner) {
        if (amount < 0) throw new IllegalArgumentException("Negative input count");
        int difference = amount - size();
        if (difference > 0) add(addedOwner, difference);
        while (difference < 0 && !parts.isEmpty()) {
            var last = parts.removeLast();
            int removed = Math.min(-difference, last.amount());
            if (removed < last.amount()) parts.add(new Part(last.owner(), last.amount() - removed, last.fallbackAllowed()));
            difference += removed;
        }
    }
    UUID consume() {
        return consume(null);
    }
    UUID consume(UUID fallback) {
        if (parts.isEmpty()) return null;
        var first = parts.removeFirst();
        if (first.amount() > 1) parts.addFirst(new Part(first.owner(), first.amount() - 1, first.fallbackAllowed()));
        return first.owner() != null ? first.owner() : first.fallbackAllowed() ? fallback : null;
    }
}
