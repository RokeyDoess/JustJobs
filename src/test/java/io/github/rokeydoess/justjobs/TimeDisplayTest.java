package io.github.rokeydoess.justjobs;

import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TimeDisplayTest {
    private static final ZoneId UTC = ZoneId.of("UTC");
    private static long at(String value) { return Instant.parse(value).toEpochMilli(); }

    @Test void twelveHourClockUsesAmPmAndHandlesMidnightAndNoon() {
        var display = new TimeDisplay(12);
        assertEquals("Tue 12:00 AM UTC", display.format(at("2026-10-27T00:00:00Z"), UTC));
        assertEquals("Tue 12:00 PM UTC", display.format(at("2026-10-27T12:00:00Z"), UTC));
        assertEquals("Tue 1:05 PM UTC", display.format(at("2026-10-27T13:05:00Z"), UTC));
    }
    @Test void twentyFourHourClockKeepsLeadingZerosWithoutAmPm() {
        var display = new TimeDisplay(24);
        assertEquals("Tue 00:00 UTC", display.format(at("2026-10-27T00:00:00Z"), UTC));
        assertEquals("Tue 09:05 UTC", display.format(at("2026-10-27T09:05:00Z"), UTC));
        assertEquals("Tue 13:05 UTC", display.format(at("2026-10-27T13:05:00Z"), UTC));
    }
    @Test void timezoneAndDayFollowTheServerClock() {
        var zone = ZoneId.of("America/Los_Angeles");
        assertEquals("Mon 5:05 PM PDT", new TimeDisplay(12).format(at("2026-10-27T00:05:00Z"), zone));
        assertEquals("Mon 17:05 PDT", new TimeDisplay(24).format(at("2026-10-27T00:05:00Z"), zone));
    }
    @Test void rejectsUnsupportedClockFormats() {
        for (int hours : new int[]{0, 1, 13, 23, 48}) {
            var error = assertThrows(IllegalArgumentException.class, () -> new TimeDisplay(hours));
            assertTrue(error.getMessage().contains("display.time-format"));
        }
    }
}
