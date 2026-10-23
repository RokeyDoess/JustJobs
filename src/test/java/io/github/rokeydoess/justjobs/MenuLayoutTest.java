package io.github.rokeydoess.justjobs;

import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MenuLayoutTest {
    @Test void nineJobsFillTheMiddleThenBalanceAboveAndBelow() {
        assertEquals(List.of(19, 20, 21, 22, 23, 24, 25, 13, 31), MenuLayout.entries(9));
        assertEquals(List.of(39, 40, 41), MenuLayout.controls(3));
    }
    @Test void everyPageSizeStaysInsideTheReservedAreaAndCentered() {
        for (int count = 0; count <= 21; count++) {
            var slots = MenuLayout.entries(count);
            assertEquals(count, new HashSet<>(slots).size());
            assertTrue(slots.stream().allMatch(s -> s / 9 >= 1 && s / 9 <= 3 && s % 9 >= 1 && s % 9 <= 7));
            for (int slot : slots) assertTrue(slots.contains(slot / 9 * 9 + 8 - slot % 9));
        }
        for (int count = 0; count <= 7; count++) {
            var slots = MenuLayout.controls(count);
            assertEquals(count, slots.size());
            for (int slot : slots) {
                assertTrue(slot >= 37 && slot <= 43);
                assertTrue(slots.contains(80 - slot));
            }
        }
        assertThrows(IllegalArgumentException.class, () -> MenuLayout.entries(22));
    }
}
