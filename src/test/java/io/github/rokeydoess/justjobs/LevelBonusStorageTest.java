package io.github.rokeydoess.justjobs;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class LevelBonusStorageTest {
    @TempDir Path folder;

    @Test void reservedBonusAndClaimProgressRecoverTogetherAcrossRestart() throws Exception {
        var player = new PlayerProfile(new UUID(81, 1), "MilestoneMiner");
        player.levelBonuses.claim("mining", 49, 50, 50, new BigDecimal("0.5"));
        var jobs = Map.of("@bonus:mining:Mining level 50", new BigDecimal("25"));
        var payment = new DataStore.Payment("level50", player.id, player.name, new BigDecimal("25"), jobs);
        Path file = folder.resolve("jobs.db");
        try (var store = new DataStore(file)) { store.reserve(player.snapshot(), payment); }
        try (var store = new DataStore(file)) {
            var loaded = store.load();
            assertEquals(1, store.recoverInterrupted(loaded));
            var restored = loaded.get(player.id);
            assertEquals(0, restored.pending.compareTo(new BigDecimal("25")));
            assertEquals(0, restored.paycheck.available(true).compareTo(new BigDecimal("25")));
            assertNull(restored.levelBonuses.claim("mining", 49, 50, 50, new BigDecimal("0.5")));
            assertEquals(0, restored.levelBonuses.claim("mining", 99, 100, 50, new BigDecimal("0.5")).amount().compareTo(new BigDecimal("50")));
        }
    }

    @Test void versionSevenAddsClaimTrackingAndKeepsExistingMoney() throws Exception {
        Path file = folder.resolve("jobs.db");
        var player = new PlayerProfile(new UUID(81, 2), "ExistingMiner");
        player.pending = new BigDecimal("5.25");
        try (var store = new DataStore(file)) { store.save(List.of(player.snapshot()), Map.of()); }
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + file); var statement = db.createStatement()) {
            statement.execute("DROP TABLE level_bonus_progress");
            statement.execute("PRAGMA user_version=7");
        }
        try (var store = new DataStore(file)) {
            var restored = store.load().get(player.id);
            assertEquals(player.pending, restored.pending);
            assertNull(restored.levelBonuses.claim("mining", 225, 226, 50, new BigDecimal("0.5")));
        }
    }
}
