package io.github.rokeydoess.justjobs;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DataStoreFaultTest {
    @TempDir Path folder;
    private Connection connection(DataStore store) throws Exception {
        var field = DataStore.class.getDeclaredField("db"); field.setAccessible(true); return (Connection) field.get(store);
    }
    @Test void aReadOnlyDatabaseRejectsNewRewardsWithoutChangingSavedMoney() throws Exception {
        var player = new PlayerProfile(UUID.randomUUID(), "Reader"); player.pending = new BigDecimal("12.37");
        try (var store = new DataStore(folder.resolve("readonly.db"))) {
            store.save(List.of(player.snapshot()), Map.of());
            try (var statement = connection(store).createStatement()) { statement.execute("PRAGMA query_only=ON"); }
            player.pending = new BigDecimal("999");
            assertThrows(java.sql.SQLException.class, () -> store.save(List.of(player.snapshot()), Map.of()));
            assertEquals(new BigDecimal("12.37"), store.load().get(player.id).pending);
        }
    }
    @Test void anExternalWriterLockDoesNotPartiallyReserveAPayment() throws Exception {
        Path file = folder.resolve("locked.db");
        var player = new PlayerProfile(UUID.randomUUID(), "Locked"); player.pending = BigDecimal.TEN;
        try (var store = new DataStore(file); var other = DriverManager.getConnection("jdbc:sqlite:" + file)) {
            store.save(List.of(player.snapshot()), Map.of());
            try (var statement = connection(store).createStatement()) { statement.execute("PRAGMA busy_timeout=50"); }
            try (var statement = other.createStatement()) { statement.execute("BEGIN IMMEDIATE"); }
            var payment = new DataStore.Payment("locked-attempt", player.id, player.name, BigDecimal.TEN);
            player.pending = BigDecimal.ZERO;
            player.paycheck.take(BigDecimal.TEN);
            assertThrows(java.sql.SQLException.class, () -> store.reserve(player.snapshot(), payment));
            assertEquals(BigDecimal.TEN, store.load().get(player.id).pending);
            assertTrue(store.unresolved().isEmpty());
            try (var statement = other.createStatement()) { statement.execute("ROLLBACK"); }
            store.reserve(player.snapshot(), payment);
            assertEquals(1, store.unresolved().size());
        }
    }
    @Test void aFullDatabaseRollsBackTheWholeSave() throws Exception {
        var player = new PlayerProfile(UUID.randomUUID(), "Full"); player.pending = BigDecimal.ONE;
        try (var store = new DataStore(folder.resolve("full.db"))) {
            store.save(List.of(player.snapshot()), Map.of());
            long pages;
            try (var statement = connection(store).createStatement(); var rows = statement.executeQuery("PRAGMA page_count")) { rows.next(); pages = rows.getLong(1); }
            try (var statement = connection(store).createStatement()) { statement.execute("PRAGMA max_page_count=" + pages); }
            for (int i = 0; i < 20_000; i++) player.xp.put("custom_" + i, (double) i);
            player.pending = BigDecimal.TEN;
            assertThrows(java.sql.SQLException.class, () -> store.save(List.of(player.snapshot()), Map.of()));
            var loaded = store.load().get(player.id);
            assertEquals(BigDecimal.ONE, loaded.pending); assertTrue(loaded.xp.isEmpty());
        }
    }
    @Test void corruptDataIsNotReplacedAndTheFailedOpenReleasesItsFile() throws Exception {
        Path file = folder.resolve("corrupt.db"); byte[] original = "this is not a SQLite database".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(file, original);
        assertThrows(Exception.class, () -> new DataStore(file));
        assertArrayEquals(original, Files.readAllBytes(file));
        Files.delete(file);
    }
    @Test void aBackupIncludesWalChangesAndCanBeRestoredWithPendingPayments() throws Exception {
        Path file = folder.resolve("source.db"), backup = folder.resolve("backup.db");
        var player = new PlayerProfile(UUID.randomUUID(), "Backup"); player.pending = new BigDecimal("0.37");
        var payment = new DataStore.Payment("backup-payment", player.id, player.name, BigDecimal.ONE);
        try (var store = new DataStore(file)) { store.reserve(player.snapshot(), payment); store.backup(backup); }
        try (var restored = new DataStore(backup)) {
            assertEquals(1, restored.recoverInterrupted(restored.load()));
            assertEquals(new BigDecimal("1.37"), restored.load().get(player.id).pending);
            assertEquals(0, restored.recoverInterrupted(restored.load()));
        }
    }
}
