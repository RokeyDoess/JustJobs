package io.github.rokeydoess.justjobs;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Accessed only on startup and by the single storage worker. */
public final class DataStore implements AutoCloseable {
    private final Connection db;
    public record Payment(String id, UUID player, String name, BigDecimal amount, Map<String, BigDecimal> jobs) {
        public Payment { jobs = Map.copyOf(jobs); if (Paycheck.total(jobs).compareTo(amount) != 0) throw new IllegalArgumentException("Invalid payment breakdown"); }
        public Payment(String id, UUID player, String name, BigDecimal amount) { this(id, player, name, amount, Map.of(Paycheck.PREVIOUS, amount)); }
    }
    public record Reservation(PlayerProfile.Snapshot player, Payment payment) { }
    public record Settlement(String id, String status, PlayerProfile.Snapshot player) { }

    public DataStore(Path file) throws Exception {
        Class.forName("org.sqlite.JDBC");
        db = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        try { initialize(); }
        catch (Exception e) {
            try { db.close(); } catch (SQLException close) { e.addSuppressed(close); }
            throw e;
        }
    }
    private void initialize() throws SQLException {
        try (var s = db.createStatement(); var rows = s.executeQuery("PRAGMA user_version")) {
            if (rows.next() && rows.getInt(1) > 4) {
                throw new SQLException("This jobs.db belongs to a newer JustJobs version");
            }
        }
        try (var s = db.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA synchronous=FULL");
            s.execute("PRAGMA busy_timeout=2000");
            s.execute("PRAGMA foreign_keys=ON");
            s.execute("CREATE TABLE IF NOT EXISTS players (uuid TEXT PRIMARY KEY, name TEXT NOT NULL, notifications INTEGER NOT NULL, pending TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS progress (uuid TEXT NOT NULL REFERENCES players(uuid), job TEXT NOT NULL, xp REAL NOT NULL, PRIMARY KEY(uuid,job))");
            s.execute("CREATE TABLE IF NOT EXISTS payments (id TEXT PRIMARY KEY, uuid TEXT NOT NULL REFERENCES players(uuid), name TEXT NOT NULL, amount TEXT NOT NULL, status TEXT NOT NULL, created TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)");
            s.execute("CREATE TABLE IF NOT EXISTS pvp_pairs (pair TEXT PRIMARY KEY, rewarded_at INTEGER NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS earnings (uuid TEXT NOT NULL REFERENCES players(uuid), job TEXT NOT NULL, minute INTEGER NOT NULL, amount TEXT NOT NULL, PRIMARY KEY(uuid,job,minute))");
            s.execute("CREATE TABLE IF NOT EXISTS travel (uuid TEXT NOT NULL REFERENCES players(uuid), job TEXT NOT NULL, distance REAL NOT NULL, PRIMARY KEY(uuid,job))");
            s.execute("CREATE TABLE IF NOT EXISTS paycheck_jobs (uuid TEXT NOT NULL REFERENCES players(uuid), job TEXT NOT NULL, amount TEXT NOT NULL, due INTEGER NOT NULL, PRIMARY KEY(uuid,job,due))");
            s.execute("CREATE TABLE IF NOT EXISTS payment_jobs (payment TEXT NOT NULL REFERENCES payments(id) ON DELETE CASCADE, job TEXT NOT NULL, amount TEXT NOT NULL, PRIMARY KEY(payment,job))");
        }
        var columns = new java.util.HashSet<String>();
        try (var s = db.createStatement(); var rows = s.executeQuery("PRAGMA table_info(players)")) {
            while (rows.next()) columns.add(rows.getString("name"));
        }
        try (var s = db.createStatement()) {
            if (!columns.contains("boss_bar")) s.execute("ALTER TABLE players ADD COLUMN boss_bar INTEGER NOT NULL DEFAULT 1");
            if (!columns.contains("payday_chat")) s.execute("ALTER TABLE players ADD COLUMN payday_chat INTEGER NOT NULL DEFAULT 1");
            if (!columns.contains("payday_at")) s.execute("ALTER TABLE players ADD COLUMN payday_at INTEGER NOT NULL DEFAULT 0");
            s.execute("UPDATE payments SET status='RESERVED' WHERE status='REVIEW'");
            s.execute("PRAGMA user_version=4");
        }
    }

    public Map<UUID, PlayerProfile> load() throws SQLException {
        var result = new HashMap<UUID, PlayerProfile>();
        try (var s = db.createStatement(); var rows = s.executeQuery("SELECT * FROM players")) {
            while (rows.next()) {
                var p = new PlayerProfile(UUID.fromString(rows.getString("uuid")), rows.getString("name"));
                p.notifications = rows.getBoolean("notifications");
                p.bossBar = rows.getBoolean("boss_bar");
                p.paydayChat = rows.getBoolean("payday_chat");
                p.paycheck.restoreNext(rows.getLong("payday_at"));
                p.pending = new BigDecimal(rows.getString("pending"));
                if (p.pending.signum() < 0) throw new SQLException("Negative pending balance for " + p.id);
                result.put(p.id, p);
            }
        }
        try (var s = db.createStatement(); var rows = s.executeQuery("SELECT * FROM progress")) {
            while (rows.next()) {
                double xp = rows.getDouble("xp");
                if (!Double.isFinite(xp) || xp < 0) throw new SQLException("Invalid saved XP");
                player(result, rows.getString("uuid")).xp.put(rows.getString("job"), xp);
            }
        }
        try (var s = db.prepareStatement("SELECT * FROM earnings WHERE minute >= ?")) {
            s.setLong(1, Math.floorDiv(System.currentTimeMillis(), 60_000) - 60);
            try (var rows = s.executeQuery()) {
                while (rows.next()) player(result, rows.getString("uuid")).earnings.restore(
                        rows.getString("job"), rows.getLong("minute"), new BigDecimal(rows.getString("amount")));
            }
        }
        try (var s = db.createStatement(); var rows = s.executeQuery("SELECT * FROM travel")) {
            while (rows.next()) player(result, rows.getString("uuid")).travel.restore(rows.getString("job"), rows.getDouble("distance"));
        }
        try (var s = db.createStatement(); var rows = s.executeQuery("SELECT * FROM paycheck_jobs")) {
            while (rows.next()) {
                var p = player(result, rows.getString("uuid"));
                p.paycheck.restore(p.paycheck.snapshot().next(), rows.getString("job"), new BigDecimal(rows.getString("amount")), rows.getBoolean("due"));
            }
        }
        for (var p : result.values()) p.paycheck.reconcile(p.pending);
        return result;
    }
    private static PlayerProfile player(Map<UUID, PlayerProfile> players, String id) throws SQLException {
        try {
            var profile = players.get(UUID.fromString(id));
            if (profile == null) throw new SQLException("Saved job data refers to a missing player: " + id);
            return profile;
        } catch (IllegalArgumentException e) { throw new SQLException("Invalid saved player UUID: " + id, e); }
    }

    private void write(PlayerProfile.Snapshot p) throws SQLException {
        try (var s = db.prepareStatement("INSERT INTO players (uuid,name,notifications,pending,boss_bar,payday_chat,payday_at) VALUES (?,?,?,?,?,?,?) ON CONFLICT(uuid) DO UPDATE SET name=excluded.name, notifications=excluded.notifications, pending=excluded.pending, boss_bar=excluded.boss_bar, payday_chat=excluded.payday_chat, payday_at=excluded.payday_at")) {
            s.setString(1, p.id().toString()); s.setString(2, p.name()); s.setBoolean(3, p.notifications()); s.setString(4, p.pending().toPlainString()); s.setBoolean(5, p.bossBar());
            s.setBoolean(6, p.paydayChat()); s.setLong(7, p.paycheck().next()); s.executeUpdate();
        }
        try (var s = db.prepareStatement("INSERT INTO progress VALUES (?,?,?) ON CONFLICT(uuid,job) DO UPDATE SET xp=excluded.xp")) {
            for (var entry : p.xp().entrySet()) {
                s.setString(1, p.id().toString()); s.setString(2, entry.getKey()); s.setDouble(3, entry.getValue()); s.addBatch();
            }
            s.executeBatch();
        }
        try (var s = db.prepareStatement("DELETE FROM paycheck_jobs WHERE uuid=?")) { s.setString(1, p.id().toString()); s.executeUpdate(); }
        try (var s = db.prepareStatement("INSERT INTO paycheck_jobs VALUES (?,?,?,?)")) {
            for (boolean due : List.of(false, true)) for (var entry : (due ? p.paycheck().due() : p.paycheck().current()).entrySet()) {
                s.setString(1, p.id().toString()); s.setString(2, entry.getKey()); s.setString(3, entry.getValue().toPlainString()); s.setBoolean(4, due); s.addBatch();
            }
            s.executeBatch();
        }
        try (var s = db.prepareStatement("INSERT INTO earnings VALUES (?,?,?,?) ON CONFLICT(uuid,job,minute) DO UPDATE SET amount=excluded.amount")) {
            for (var job : p.earnings().entrySet()) for (var minute : job.getValue().entrySet()) {
                s.setString(1, p.id().toString()); s.setString(2, job.getKey()); s.setLong(3, minute.getKey());
                s.setString(4, minute.getValue().toPlainString()); s.addBatch();
            }
            s.executeBatch();
        }
        try (var s = db.prepareStatement("DELETE FROM travel WHERE uuid=?")) { s.setString(1, p.id().toString()); s.executeUpdate(); }
        try (var s = db.prepareStatement("INSERT INTO travel VALUES (?,?,?)")) {
            for (var entry : p.travel().entrySet()) {
                s.setString(1, p.id().toString()); s.setString(2, entry.getKey()); s.setDouble(3, entry.getValue()); s.addBatch();
            }
            s.executeBatch();
        }
    }

    @FunctionalInterface private interface Work { void run() throws SQLException; }
    private void transaction(Work work) throws SQLException {
        db.setAutoCommit(false);
        try { work.run(); db.commit(); }
        catch (SQLException | RuntimeException e) {
            try { db.rollback(); } catch (SQLException rollback) { e.addSuppressed(rollback); }
            throw e;
        }
        finally { db.setAutoCommit(true); }
    }

    public void save(List<PlayerProfile.Snapshot> players, Map<String, Long> pairs) throws SQLException {
        transaction(() -> {
            for (var p : players) write(p);
            try (var s = db.prepareStatement("INSERT INTO pvp_pairs VALUES (?,?) ON CONFLICT(pair) DO UPDATE SET rewarded_at=excluded.rewarded_at")) {
                for (var entry : pairs.entrySet()) { s.setString(1, entry.getKey()); s.setLong(2, entry.getValue()); s.addBatch(); }
                s.executeBatch();
            }
        });
    }

    public Map<String, Long> loadPairs() throws SQLException {
        var pairs = new HashMap<String, Long>();
        try (var s = db.createStatement(); var rows = s.executeQuery("SELECT * FROM pvp_pairs")) {
            while (rows.next()) pairs.put(rows.getString(1), rows.getLong(2));
        }
        return pairs;
    }

    public void prune(long now) throws SQLException {
        transaction(() -> {
            try (var s = db.prepareStatement("DELETE FROM earnings WHERE minute < ?")) {
                s.setLong(1, Math.floorDiv(now, 60_000) - 60); s.executeUpdate();
            }
            try (var s = db.prepareStatement("DELETE FROM pvp_pairs WHERE rewarded_at < ?")) {
                s.setLong(1, now - 86_400_000); s.executeUpdate();
            }
            try (var s = db.createStatement()) {
                s.executeUpdate("DELETE FROM payments WHERE status != 'RESERVED' AND created < datetime('now','-7 days')");
            }
        });
    }

    /** Vault has no idempotency key. Prefer retrying an interrupted deposit to losing earned money. */
    public int recoverInterrupted(Map<UUID, PlayerProfile> players) throws SQLException {
        var interrupted = unresolved();
        transaction(() -> {
            for (var payment : interrupted) {
                var profile = players.get(payment.player());
                profile.pending = profile.pending.add(payment.amount());
                profile.paycheck.refund(payment.jobs());
                write(profile.snapshot());
                try (var s = db.prepareStatement("UPDATE payments SET status='RETRIED' WHERE id=? AND status='RESERVED'")) {
                    s.setString(1, payment.id()); s.executeUpdate();
                }
            }
        });
        return interrupted.size();
    }

    public void reserve(PlayerProfile.Snapshot player, Payment payment) throws SQLException {
        reserve(List.of(new Reservation(player, payment)));
    }
    public void reserve(List<Reservation> reservations) throws SQLException {
        transaction(() -> {
            try (var s = db.prepareStatement("INSERT INTO payments (id,uuid,name,amount,status) VALUES (?,?,?,?,'RESERVED')")) {
                for (var reservation : reservations) {
                    write(reservation.player());
                    var payment = reservation.payment();
                    s.setString(1, payment.id()); s.setString(2, payment.player().toString()); s.setString(3, payment.name()); s.setString(4, payment.amount().toPlainString()); s.addBatch();
                }
                s.executeBatch();
            }
            try (var s = db.prepareStatement("INSERT INTO payment_jobs VALUES (?,?,?)")) {
                for (var reservation : reservations) for (var entry : reservation.payment().jobs().entrySet()) {
                    s.setString(1, reservation.payment().id()); s.setString(2, entry.getKey()); s.setString(3, entry.getValue().toPlainString()); s.addBatch();
                }
                s.executeBatch();
            }
        });
    }

    public void settle(String id, String status, PlayerProfile.Snapshot player) throws SQLException {
        settle(List.of(new Settlement(id, status, player)));
    }
    public void settle(List<Settlement> settlements) throws SQLException {
        transaction(() -> {
            try (var s = db.prepareStatement("UPDATE payments SET status=? WHERE id=? AND status='RESERVED'")) {
                for (var settlement : settlements) {
                    write(settlement.player());
                    s.setString(1, settlement.status()); s.setString(2, settlement.id());
                    if (s.executeUpdate() != 1) throw new SQLException("Payment has already been settled: " + settlement.id());
                }
            }
        });
    }

    public List<Payment> unresolved() throws SQLException {
        var breakdowns = new HashMap<String, Map<String, BigDecimal>>();
        try (var s = db.createStatement(); var rows = s.executeQuery("SELECT j.* FROM payment_jobs j JOIN payments p ON p.id=j.payment WHERE p.status='RESERVED'")) {
            while (rows.next()) breakdowns.computeIfAbsent(rows.getString("payment"), ignored -> new HashMap<>()).put(rows.getString("job"), new BigDecimal(rows.getString("amount")));
        }
        var payments = new ArrayList<Payment>();
        try (var s = db.createStatement(); var rows = s.executeQuery("SELECT * FROM payments WHERE status='RESERVED' ORDER BY created,id")) {
            while (rows.next()) {
                String id = rows.getString("id"); BigDecimal amount = new BigDecimal(rows.getString("amount"));
                payments.add(new Payment(id, UUID.fromString(rows.getString("uuid")), rows.getString("name"), amount, breakdowns.getOrDefault(id, Map.of(Paycheck.PREVIOUS, amount))));
            }
        }
        return payments;
    }
    public void backup(Path target) throws Exception {
        // VACUUM INTO includes committed WAL data; copying jobs.db alone would miss it.
        Path temp = target.toAbsolutePath().resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            try (var statement = db.prepareStatement("VACUUM INTO ?")) { statement.setString(1, temp.toString()); statement.execute(); }
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + temp); var statement = connection.createStatement(); var rows = statement.executeQuery("PRAGMA quick_check")) {
                if (!rows.next() || !"ok".equals(rows.getString(1))) throw new SQLException("Backup integrity check failed");
            }
            try (var channel = java.nio.channels.FileChannel.open(temp, java.nio.file.StandardOpenOption.WRITE)) { channel.force(true); }
            java.nio.file.Files.move(temp, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally { java.nio.file.Files.deleteIfExists(temp); }
    }

    @Override public void close() throws SQLException { db.close(); }
}
