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
        for (int count = 0; count <= 27; count++) {
            var slots = MenuLayout.entries(count);
            assertEquals(count, new HashSet<>(slots).size());
            assertTrue(slots.stream().allMatch(s -> s >= 9 && s <= 35));
            if (count <= 21) for (int slot : slots) assertTrue(slots.contains(slot / 9 * 9 + 8 - slot % 9));
            else assertEquals(MenuLayout.entries(21), slots.subList(0, 21));
        }
        for (int count = 0; count <= 7; count++) {
            var slots = MenuLayout.controls(count);
            assertEquals(count, slots.size());
            for (int slot : slots) {
                assertTrue(slot >= 37 && slot <= 43);
                assertTrue(slots.contains(80 - slot));
            }
        }
        assertThrows(IllegalArgumentException.class, () -> MenuLayout.entries(28));
    }
    @Test void overflowUsesSideSlotsAndRanksReadAcrossAllNineColumns() {
        assertEquals(List.of(18, 26, 9, 17, 27, 35), MenuLayout.entries(27).subList(21, 27));
        assertEquals(java.util.stream.IntStream.rangeClosed(9, 35).boxed().toList(), MenuLayout.ranks(27));
        assertEquals(List.of(9, 10, 11), MenuLayout.ranks(3));
    }
}
