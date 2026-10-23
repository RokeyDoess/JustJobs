package io.github.rokeydoess.justjobs;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DataStoreCrashTest {
    @TempDir Path folder;
    static final UUID PLAYER = UUID.fromString("b7f3e4bc-bf84-4100-93bc-0116c2fd751a");

    @Test void abruptExitBeforeReservationKeepsTheSavedBalance() throws Exception { checkCrash("saved", "12.37", 0); }
    @Test void abruptExitAfterReservationRecoversExactlyOnce() throws Exception { checkCrash("reserved", "12.37", 1); }
    @Test void abruptExitAfterSettlementDoesNotRepeatPayment() throws Exception { checkCrash("settled", "0.37", 0); }

    void checkCrash(String stage, String expected, int recoveries) throws Exception {
        Path file = folder.resolve(stage + ".db");
        String executable = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        var process = new ProcessBuilder(executable, "-cp", classpath, CrashingWriter.class.getName(), file.toString(), stage)
                .redirectErrorStream(true).redirectOutput(folder.resolve(stage + ".log").toFile()).start();
        if (!process.waitFor(20, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("Crash-test child did not finish"); }
        assertEquals(27, process.exitValue(), () -> {
            try { return java.nio.file.Files.readString(folder.resolve(stage + ".log")); } catch (Exception e) { return e.toString(); }
        });
        try (var store = new DataStore(file)) {
            assertEquals(recoveries, store.recoverInterrupted(store.load()));
            var loaded = store.load().get(PLAYER);
            assertEquals(new BigDecimal(expected), loaded.pending);
            assertEquals(42.5, loaded.xp("digging"));
            assertEquals(149.5, loaded.travel.snapshot().get("exploring"));
            assertEquals(new BigDecimal("99.50"), loaded.earnings.total("hunting", System.currentTimeMillis()));
            assertTrue(store.unresolved().isEmpty());
        }
        try (var store = new DataStore(file)) { assertEquals(0, store.recoverInterrupted(store.load())); }
    }
    public static class CrashingWriter {
        public static void main(String[] args) throws Exception {
            var store = new DataStore(Path.of(args[0]));
            var player = new PlayerProfile(PLAYER, "CrashCheck");
            player.pending = new BigDecimal("12.37");
            player.xp.put("digging", 42.5);
            player.travel.add("exploring", 149.5, 150);
            player.earnings.credit("hunting", new BigDecimal("99.50"), new BigDecimal("100"), System.currentTimeMillis());
            store.save(List.of(player.snapshot()), Map.of());
            if (!args[1].equals("saved")) {
                player.pending = new BigDecimal("0.37");
                store.reserve(player.snapshot(), new DataStore.Payment("crash-payment", PLAYER, player.name, new BigDecimal("12")));
                if (args[1].equals("settled")) store.settle("crash-payment", "PAID", player.snapshot());
            }
            // Deliberately bypass shutdown hooks, connection close and WAL checkpointing.
            Runtime.getRuntime().halt(27);
        }
    }
}
