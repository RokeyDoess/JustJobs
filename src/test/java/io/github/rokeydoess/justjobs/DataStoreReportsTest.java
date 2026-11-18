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

class DataStoreReportsTest {
    @TempDir Path dir;

    @Test void versionSixUpgradesWithoutChangingSavedProgressOrUnpaidMoney() throws Exception {
        Path file = dir.resolve("jobs.db");
        var player = new PlayerProfile(new UUID(71, 6), "ExistingPlayer");
        player.pending = new BigDecimal("12.375");
        player.paycheck.earn("mining", player.pending, 1, 3_600_000, true);
        player.xp.put("mining", 125.0);
        try (var store = new DataStore(file)) { store.save(List.of(player.snapshot(2)), Map.of()); }
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + file); var statement = db.createStatement()) {
            for (String table : List.of("receipt_jobs", "receipts", "job_economy", "reporting_metadata"))
                statement.execute("DROP TABLE " + table);
            statement.execute("PRAGMA user_version=6");
        }
        try (var store = new DataStore(file)) {
            var restored = store.load().get(player.id);
            assertEquals(player.pending, restored.pending);
            assertEquals(player.xp, restored.xp);
            assertEquals(player.paycheck.snapshot(), restored.paycheck.snapshot());
            assertTrue(restored.economy.isEmpty());
            assertTrue(store.history(player.id).isEmpty());
            assertTrue(store.economySince() > 0);
        }
    }

    @Test void economyTotalsAndPartialReceiptsSurviveRestartWithoutCountingRetriesTwice() throws Exception {
        var player = new PlayerProfile(new UUID(71, 1), "Miner");
        var stats = player.economy("mining");
        stats.earnings(new BigDecimal("5"), new BigDecimal("2"), true);
        Path file = dir.resolve("jobs.db");
        long since;
        try (var store = new DataStore(file)) {
            since = store.economySince();
            var payment = new DataStore.Payment("first", player.id, player.name, new BigDecimal("5"), Map.of("mining", new BigDecimal("5")));
            store.reserve(player.snapshot(), payment);
            stats.paid(new BigDecimal("2"));
            var settlement = new DataStore.Settlement(payment.id(), "PARTIAL", player.snapshot(), Map.of("mining", new BigDecimal("2")), 100);
            store.settle(List.of(settlement));
            assertThrows(java.sql.SQLException.class, () -> store.settle(List.of(settlement)));
            var retry = new DataStore.Payment("retry", player.id, player.name, new BigDecimal("3"), Map.of("mining", new BigDecimal("3")));
            store.reserve(player.snapshot(), retry);
            stats.paid(new BigDecimal("3"));
            store.settle(List.of(new DataStore.Settlement(retry.id(), "PAID", player.snapshot(), retry.jobs(), 200)));
        }
        try (var store = new DataStore(file)) {
            assertEquals(since, store.economySince());
            var loaded = store.load().get(player.id).economy("mining").snapshot();
            assertEquals(new BigDecimal("5"), loaded.earned());
            assertEquals(new BigDecimal("2"), loaded.capped());
            assertEquals(new BigDecimal("5"), loaded.paid());
            assertEquals(1, loaded.capHits());
            var receipts = store.history(player.id);
            assertEquals(List.of("retry", "first"), receipts.stream().map(DataStore.Receipt::id).toList());
            assertEquals(new BigDecimal("3"), receipts.getFirst().amount());
            assertEquals(new BigDecimal("2"), receipts.getLast().amount());
        }
    }

    @Test void failedAndInterruptedPaymentsDoNotCreateReceipts() throws Exception {
        var player = new PlayerProfile(new UUID(71, 2), "Fisher");
        try (var store = new DataStore(dir.resolve("jobs.db"))) {
            var payment = new DataStore.Payment("rejected", player.id, player.name, BigDecimal.ONE);
            store.reserve(player.snapshot(), payment);
            store.settle(List.of(new DataStore.Settlement(payment.id(), "REFUNDED", player.snapshot(), Map.of(), 10)));
            store.reserve(player.snapshot(), new DataStore.Payment("interrupted", player.id, player.name, BigDecimal.ONE));
            assertEquals(1, store.recoverInterrupted(store.load()));
            assertTrue(store.history(player.id).isEmpty());
        }
    }

    @Test void aReceiptWriteFailureRollsBackPaymentStatusAndSavedTotalsTogether() throws Exception {
        Path file = dir.resolve("jobs.db");
        var player = new PlayerProfile(new UUID(71, 5), "Trader");
        player.economy("trading").earnings(BigDecimal.ONE, BigDecimal.ZERO, false);
        try (var store = new DataStore(file); var db = DriverManager.getConnection("jdbc:sqlite:" + file);
             var statement = db.createStatement()) {
            var payment = new DataStore.Payment("atomic", player.id, player.name, BigDecimal.ONE, Map.of("trading", BigDecimal.ONE));
            store.reserve(player.snapshot(), payment);
            statement.execute("CREATE TRIGGER reject_receipt BEFORE INSERT ON receipts BEGIN SELECT RAISE(ABORT,'receipt unavailable'); END");
            player.economy("trading").paid(BigDecimal.ONE);
            var settlement = new DataStore.Settlement(payment.id(), "PAID", player.snapshot(), payment.jobs(), 10);
            assertThrows(java.sql.SQLException.class, () -> store.settle(List.of(settlement)));
            assertTrue(store.history(player.id).isEmpty());
            assertEquals(1, store.unresolved().size());
            assertEquals(0, store.load().get(player.id).economy("trading").snapshot().paid().signum());
            statement.execute("DROP TRIGGER reject_receipt");
            store.settle(List.of(settlement));
            assertEquals(1, store.history(player.id).size());
            assertTrue(store.unresolved().isEmpty());
        }
    }

    @Test void historyRetainsThirtyDepositsPerPlayerIncludingBonusBreakdowns() throws Exception {
        var player = new PlayerProfile(new UUID(71, 3), "Hunter");
        var other = new PlayerProfile(new UUID(71, 4), "Builder");
        Path file = dir.resolve("jobs.db");
        try (var store = new DataStore(file)) {
            for (int i = 0; i < 35; i++) {
                var payment = new DataStore.Payment("paid" + i, player.id, player.name, BigDecimal.ONE,
                        Map.of("@bonus:hunting:Wither", BigDecimal.ONE));
                store.reserve(player.snapshot(), payment);
                store.settle(List.of(new DataStore.Settlement(payment.id(), "PAID", player.snapshot(), payment.jobs(), 100 + i)));
            }
            var payment = new DataStore.Payment("other", other.id, other.name, BigDecimal.ONE);
            store.reserve(other.snapshot(), payment);
            store.settle(List.of(new DataStore.Settlement(payment.id(), "PAID", other.snapshot(), payment.jobs(), 1)));
            assertEquals(30, store.history(player.id).size());
            assertEquals("paid34", store.history(player.id).getFirst().id());
            assertEquals("paid5", store.history(player.id).getLast().id());
            assertTrue(store.history(player.id).getFirst().bonus());
            assertEquals(1, store.history(other.id).size());
            store.prune(System.currentTimeMillis());
            store.backup(dir.resolve("backup.db"));
        }
        try (var backup = new DataStore(dir.resolve("backup.db"))) {
            assertEquals(30, backup.history(player.id).size());
        }
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + file); var statement = db.createStatement();
             var rows = statement.executeQuery("SELECT COUNT(*) FROM receipt_jobs")) {
            assertTrue(rows.next()); assertEquals(31, rows.getInt(1));
        }
    }
}
