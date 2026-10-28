package io.github.rokeydoess.justjobs;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Shared formatting for clock times shown to players. */
final class TimeDisplay {
    private static final DateTimeFormatter TWELVE_HOUR = DateTimeFormatter.ofPattern("EEE h:mm a z", Locale.ENGLISH);
    private static final DateTimeFormatter TWENTY_FOUR_HOUR = DateTimeFormatter.ofPattern("EEE HH:mm z", Locale.ENGLISH);
    private final DateTimeFormatter formatter;

    TimeDisplay(int hours) {
        if (hours != 12 && hours != 24) throw new IllegalArgumentException("display.time-format must be 12 or 24");
        formatter = hours == 12 ? TWELVE_HOUR : TWENTY_FOUR_HOUR;
    }

    String format(long timestamp, ZoneId zone) {
        return formatter.withZone(zone).format(Instant.ofEpochMilli(timestamp));
    }
}
