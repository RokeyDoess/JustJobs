package io.github.rokeydoess.justjobs;

import java.util.ArrayList;
import java.util.List;

final class MenuLayout {
    static final int SIZE = 45, CAPACITY = 27, INFO = 4, SETTINGS = 36, CLOSE = 44;
    private MenuLayout() { }
    static List<Integer> entries(int count) {
        if (count < 0 || count > CAPACITY) throw new IllegalArgumentException("Menu holds at most 27 entries");
        int middle = Math.min(7, count), remaining = Math.min(21, count) - middle;
        var slots = new ArrayList<Integer>(count);
        slots.addAll(centered(2, middle));
        slots.addAll(centered(1, (remaining + 1) / 2));
        slots.addAll(centered(3, remaining / 2));
        if (count > 21) slots.addAll(List.of(18, 26, 9, 17, 27, 35).subList(0, count - 21));
        return List.copyOf(slots);
    }
    static List<Integer> controls(int count) { return centered(4, count); }
    static List<Integer> ranks(int count) {
        if (count < 0 || count > CAPACITY) throw new IllegalArgumentException("Menu holds at most 27 entries");
        var slots = new ArrayList<Integer>(count);
        for (int row = 1; row <= 3 && slots.size() < count; row++)
            for (int column = 0; column <= 8 && slots.size() < count; column++) slots.add(row * 9 + column);
        return List.copyOf(slots);
    }
    private static List<Integer> centered(int row, int count) {
        if (count < 0 || count > 7) throw new IllegalArgumentException("Row holds at most seven items");
        var slots = new ArrayList<Integer>(count);
        int start = 4 - count / 2;
        for (int i = 0; i < count; i++) {
            int gap = count % 2 == 0 && i >= count / 2 ? 1 : 0;
            slots.add(row * 9 + start + i + gap);
        }
        return List.copyOf(slots);
    }
}
