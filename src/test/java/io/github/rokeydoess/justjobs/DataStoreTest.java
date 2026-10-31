package io.github.rokeydoess.justjobs;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DataStoreTest {
    @TempDir Path dir;
    @Test void bonusBalancesSurviveRestartWithoutUsingTheNormalCap() throws Exception {
        var player = new PlayerProfile(new UUID(30, 1), "BossHunter");
        player.pending = new BigDecimal("75");
        player.paycheck.earn("hunting", new BigDecimal("25"), new BigDecimal("25"), 1, 3_600_000, true);
        player.paycheck.earnBonus("hunting", "Hunting: Ender Dragon", new BigDecimal("50"), 2, 3_600_000, true);
        Path file = dir.resolve("jobs.db");
        try (var store = new DataStore(file)) { store.save(List.of(player.snapshot(3)), Map.of()); }
        try (var store = new DataStore(file)) {
            var loaded = store.load().get(player.id);
            assertEquals(0, loaded.paycheck.earned("hunting", 3).compareTo(new BigDecimal("25")));
            assertEquals(Map.of("Hunting: Ender Dragon", new BigDecimal("50")), Paycheck.bonusSources(loaded.paycheck.snapshot().due()));
            loaded.paycheck.advance(3_600_000, 3_600_000, true);
            assertEquals(Map.of("hunting", new BigDecimal("75")), Paycheck.byJob(loaded.paycheck.take(new BigDecimal("75"))));
        }
    }
    @Test void progressNamesPreferencesAndFractionsSurviveRestart() throws Exception {
        var player = new PlayerProfile(UUID.randomUUID(), "Rokey");
        player.xp.put("digging", 42.5);
        player.xp.put("custom_job", 7.0);
        player.pending = new BigDecimal("0.00012345");
        player.notifications = false;
        player.bossBar = false;
        player.paydayChat = false;
        player.timeFormat = 24;
        player.biomes.addAll(java.util.Set.of("minecraft:plains", "minecraft:forest"));
        player.travel.add("exploring", 249.5, 250);
        long now = System.currentTimeMillis();
        player.earnings.credit("hunting", new BigDecimal("99.50"), new BigDecimal("100"), now);
        Path file = dir.resolve("jobs.db");
        try (var store = new DataStore(file)) { store.save(List.of(player.snapshot()), Map.of("pair", 123L)); }
        try (var store = new DataStore(file)) {
            var loaded = store.load().get(player.id);
            assertEquals(player.xp, loaded.xp);
            assertEquals(player.pending, loaded.pending);
            assertEquals("Rokey", loaded.name);
            assertFalse(loaded.notifications);
            assertFalse(loaded.bossBar);
            assertFalse(loaded.paydayChat);
            assertEquals(24, loaded.timeFormat);
            assertEquals(player.biomes, loaded.biomes);
            assertEquals(250, loaded.travel.add("exploring", .5, 250));
            assertEquals(new BigDecimal("0.50"), loaded.earnings.credit("hunting", BigDecimal.TEN, new BigDecimal("100"), now));
            assertEquals(123L, store.loadPairs().get("pair"));
        }
    }
    @Test void interruptedPaymentsRecoverAutomaticallyOnlyOnceAcrossRestarts() throws Exception {
        var player = new PlayerProfile(UUID.randomUUID(), "Rokey");
        player.pending = new BigDecimal("0.37");
        var payment = new DataStore.Payment(UUID.randomUUID().toString(), player.id, player.name, BigDecimal.ONE);
        Path file = dir.resolve("jobs.db");
        try (var store = new DataStore(file)) { store.reserve(player.snapshot(), payment); }
        try (var store = new DataStore(file)) {
            assertEquals(new BigDecimal("0.37"), store.load().get(player.id).pending);
            assertEquals(List.of(payment), store.unresolved());
            var loaded = store.load();
            assertEquals(1, store.recoverInterrupted(loaded));
            assertTrue(store.unresolved().isEmpty());
            assertEquals(new BigDecimal("1.37"), store.load().get(player.id).pending);
            assertThrows(java.sql.SQLException.class, () -> store.settle(payment.id(), "REFUNDED", player.snapshot()));
        }
        try (var store = new DataStore(file)) {
            assertEquals(0, store.recoverInterrupted(store.load()));
            assertEquals(new BigDecimal("1.37"), store.load().get(player.id).pending);
        }
    }
    @Test void successfulPayoutKeepsNewEarningsAndSettlesOnlyOnce() throws Exception {
        var player = new PlayerProfile(UUID.randomUUID(), "Rokey");
        player.pending = new BigDecimal("0.37");
        var payment = new DataStore.Payment(UUID.randomUUID().toString(), player.id, player.name, BigDecimal.ONE);
        try (var store = new DataStore(dir.resolve("jobs.db"))) {
            store.reserve(player.snapshot(), payment);
            player.pending = player.pending.add(new BigDecimal("0.25"));
            store.settle(payment.id(), "PAID", player.snapshot());
            assertTrue(store.unresolved().isEmpty());
            assertEquals(new BigDecimal("0.62"), store.load().get(player.id).pending);
        }
    }
    @Test void failedReservationRollsBackPlayerChanges() throws Exception {
        var player = new PlayerProfile(UUID.randomUUID(), "Rokey");
        var payment = new DataStore.Payment(UUID.randomUUID().toString(), player.id, player.name, BigDecimal.ONE);
        try (var store = new DataStore(dir.resolve("jobs.db"))) {
            store.reserve(player.snapshot(), payment);
            player.pending = new BigDecimal("999");
            assertThrows(java.sql.SQLException.class, () -> store.reserve(player.snapshot(), payment));
            assertEquals(BigDecimal.ZERO, store.load().get(player.id).pending);
        }
    }
    @Test void originalDatabaseUpgradesWithoutChangingSavedRewards() throws Exception {
        Path file = dir.resolve("legacy.db");
        UUID id = UUID.randomUUID();
        Class.forName("org.sqlite.JDBC");
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + file); var s = connection.createStatement()) {
            s.execute("CREATE TABLE players (uuid TEXT PRIMARY KEY,name TEXT NOT NULL,notifications INTEGER NOT NULL,pending TEXT NOT NULL)");
            s.execute("INSERT INTO players VALUES ('" + id + "','Rokey',0,'12.37')");
        }
        try (var store = new DataStore(file)) {
            var loaded = store.load().get(id);
            assertTrue(loaded.bossBar);
            assertFalse(loaded.notifications);
            assertNull(loaded.timeFormat);
            assertEquals(new BigDecimal("12.37"), loaded.pending);
            store.save(List.of(loaded.snapshot()), Map.of());
        }
    }
    @Test void batchFailureRollsBackEveryPlayersReservation() throws Exception {
        var first = new PlayerProfile(UUID.randomUUID(), "First");
        var second = new PlayerProfile(UUID.randomUUID(), "Second");
        var payment = new DataStore.Payment("same-id", first.id, first.name, BigDecimal.ONE);
        try (var store = new DataStore(dir.resolve("batch.db"))) {
            assertThrows(java.sql.SQLException.class, () -> store.reserve(List.of(
                    new DataStore.Reservation(first.snapshot(), payment),
                    new DataStore.Reservation(second.snapshot(), new DataStore.Payment("same-id", second.id, second.name, BigDecimal.TEN)))));
            assertTrue(store.load().isEmpty());
            assertTrue(store.unresolved().isEmpty());
        }
    }
    @Test void aFutureDatabaseIsRejectedWithoutDowngradingIt() throws Exception {
        Path file = dir.resolve("future.db");
        Class.forName("org.sqlite.JDBC");
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + file); var s = connection.createStatement()) {
            s.execute("PRAGMA user_version=99");
        }
        assertThrows(java.sql.SQLException.class, () -> new DataStore(file));
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + file); var s = connection.createStatement(); var rows = s.executeQuery("PRAGMA user_version")) {
            assertTrue(rows.next()); assertEquals(99, rows.getInt(1));
        }
    }
    @Test void clockPreferencesKeepBothExplicitFormatsAndTheServerDefault() throws Exception {
        var inherited = new PlayerProfile(UUID.randomUUID(), "DefaultClock");
        var twelve = new PlayerProfile(UUID.randomUUID(), "MorningClock"); twelve.timeFormat = 12;
        var twentyFour = new PlayerProfile(UUID.randomUUID(), "EveningClock"); twentyFour.timeFormat = 24;
        Path file = dir.resolve("clocks.db");
        try (var store = new DataStore(file)) {
            store.save(List.of(inherited.snapshot(), twelve.snapshot(), twentyFour.snapshot()), Map.of());
        }
        try (var store = new DataStore(file)) {
            var loaded = store.load();
            assertNull(loaded.get(inherited.id).timeFormat);
            assertEquals(12, loaded.get(twelve.id).timeFormat);
            assertEquals(24, loaded.get(twentyFour.id).timeFormat);
            twentyFour.timeFormat = 12;
            store.save(List.of(twentyFour.snapshot()), Map.of());
            assertEquals(12, store.load().get(twentyFour.id).timeFormat);
        }
    }
    @Test void versionFourUpgradeKeepsEarningsAndLeavesClockChoiceUnset() throws Exception {
        Path file = dir.resolve("version-four.db");
        UUID id = UUID.randomUUID();
        Class.forName("org.sqlite.JDBC");
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + file); var s = connection.createStatement()) {
            s.execute("CREATE TABLE players (uuid TEXT PRIMARY KEY,name TEXT NOT NULL,notifications INTEGER NOT NULL,pending TEXT NOT NULL,boss_bar INTEGER NOT NULL,payday_chat INTEGER NOT NULL,payday_at INTEGER NOT NULL)");
            s.execute("INSERT INTO players VALUES ('" + id + "','ExistingPlayer',0,'12.37',0,0,0)");
            s.execute("CREATE TABLE progress (uuid TEXT NOT NULL REFERENCES players(uuid),job TEXT NOT NULL,xp REAL NOT NULL,PRIMARY KEY(uuid,job))");
            s.execute("INSERT INTO progress VALUES ('" + id + "','mining',42.5)");
            s.execute("PRAGMA user_version=4");
        }
        try (var store = new DataStore(file)) {
            var loaded = store.load().get(id);
            assertNull(loaded.timeFormat);
            assertEquals(new BigDecimal("12.37"), loaded.pending);
            assertEquals(42.5, loaded.xp("mining"));
            assertFalse(loaded.notifications); assertFalse(loaded.bossBar); assertFalse(loaded.paydayChat);
            loaded.timeFormat = 24;
            store.save(List.of(loaded.snapshot()), Map.of());
        }
        try (var store = new DataStore(file)) { assertEquals(24, store.load().get(id).timeFormat); }
    }
    @Test void paycheckCapSurvivesStorageEvenAfterTheRollingHourExpires() throws Exception {
        var p = new PlayerProfile(UUID.randomUUID(), "PeriodCap");
        long hour = 3_600_000, now = System.currentTimeMillis();
        long start = now - Math.floorMod(now, 2 * hour);
        var limit = new BigDecimal("100");
        p.pending = p.paycheck.earn("hunting", limit, limit, start + 1, 2 * hour, true);
        p.earnings.credit("hunting", limit, BigDecimal.ZERO, start + 1);
        Path file = dir.resolve("period-cap.db");
        try (var store = new DataStore(file)) { store.save(List.of(p.snapshot(start + hour + 120_000)), Map.of()); }
        try (var store = new DataStore(file)) {
            var loaded = store.load().get(p.id);
            assertEquals(0, loaded.earnings.total("hunting", start + hour + 120_000).signum());
            assertEquals(0, loaded.paycheck.earn("hunting", BigDecimal.ONE, limit, start + hour + 120_000, 2 * hour, true).signum());
            assertEquals(limit, loaded.paycheck.earn("hunting", limit, limit, start + 2 * hour, 2 * hour, true));
            assertEquals(limit, loaded.paycheck.available());
        }
    }
    @Test void paycheckPeriodsAndReservedBreakdownsSurviveRecovery() throws Exception {
        var p = new PlayerProfile(UUID.randomUUID(), "Paycheck");
        long now = System.currentTimeMillis();
        p.paycheck.earn("mining", new BigDecimal("12.37"), now, 3_600_000, false);
        p.paycheck.earn("fishing", new BigDecimal("2.50"), now, 3_600_000, true);
        p.pending = new BigDecimal("14.87");
        var jobs = p.paycheck.take(new BigDecimal("12")); p.pending = p.pending.subtract(new BigDecimal("12"));
        var payment = new DataStore.Payment("payday", p.id, p.name, new BigDecimal("12"), jobs);
        Path file = dir.resolve("paychecks.db");
        try (var store = new DataStore(file)) { store.reserve(p.snapshot(), payment); }
        try (var store = new DataStore(file)) {
            var loaded = store.load(); var restored = loaded.get(p.id);
            assertEquals(p.paycheck.snapshot(), restored.paycheck.snapshot());
            assertEquals(jobs, store.unresolved().getFirst().jobs());
            assertEquals(1, store.recoverInterrupted(loaded));
            restored = store.load().get(p.id);
            assertEquals(new BigDecimal("12.37"), restored.paycheck.available());
            assertEquals(new BigDecimal("2.50"), restored.paycheck.snapshot().current().get("fishing"));
            assertEquals(new BigDecimal("14.87"), restored.pending);
            assertEquals(0, store.recoverInterrupted(store.load()));
        }
    }
}
