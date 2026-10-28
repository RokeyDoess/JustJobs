package io.github.rokeydoess.justjobs;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class BossBarCooldownsTest {
    private static final UUID FIRST = new UUID(1, 1), SECOND = new UUID(1, 2);

    @Test void suppressedAwardsDoNotExtendTheQuietPeriod() {
        var cooldowns = new BossBarCooldowns();
        assertTrue(cooldowns.acquire(FIRST, "cooking", 1000, 30_000));
        for (long time = 1001; time < 31_000; time += 100)
            assertFalse(cooldowns.acquire(FIRST, "cooking", time, 30_000));
        assertFalse(cooldowns.acquire(FIRST, "cooking", 30_999, 30_000));
        assertTrue(cooldowns.acquire(FIRST, "cooking", 31_000, 30_000));
        assertFalse(cooldowns.acquire(FIRST, "cooking", 31_001, 30_000));
    }

    @Test void eachPlayerAndJobHasItsOwnTimer() {
        var cooldowns = new BossBarCooldowns();
        assertTrue(cooldowns.acquire(FIRST, "cooking", 1000, 30_000));
        assertTrue(cooldowns.acquire(FIRST, "smithing", 1000, 30_000));
        assertTrue(cooldowns.acquire(SECOND, "cooking", 1000, 30_000));
        assertFalse(cooldowns.acquire(FIRST, "cooking", 2000, 30_000));
    }

    @Test void zeroDisablesTheCooldownAndChangesApplyToExistingTimers() {
        var cooldowns = new BossBarCooldowns();
        assertTrue(cooldowns.acquire(FIRST, "cooking", 1000, 30_000));
        assertTrue(cooldowns.acquire(FIRST, "cooking", 1001, 0));
        assertTrue(cooldowns.acquire(FIRST, "cooking", 1001, 0));
        assertFalse(cooldowns.acquire(FIRST, "cooking", 2000, 60_000));
        assertTrue(cooldowns.acquire(FIRST, "cooking", 61_001, 60_000));
    }

    @Test void disconnectCleanupOnlyForgetsThatPlayer() {
        var cooldowns = new BossBarCooldowns();
        cooldowns.acquire(FIRST, "cooking", 1000, 30_000);
        cooldowns.acquire(SECOND, "cooking", 1000, 30_000);
        cooldowns.forget(FIRST);
        assertTrue(cooldowns.acquire(FIRST, "cooking", 1001, 30_000));
        assertFalse(cooldowns.acquire(SECOND, "cooking", 1001, 30_000));
    }
}
