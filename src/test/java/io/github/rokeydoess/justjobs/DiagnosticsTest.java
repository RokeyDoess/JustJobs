package io.github.rokeydoess.justjobs;

import java.math.BigDecimal;
import java.util.*;
import java.util.logging.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DiagnosticsTest {
    @Test void diagnosticsAreSilentUntilEnabledAndSummarizeRatherThanLogEachAction() {
        var lines = new ArrayList<String>();
        var logger = Logger.getAnonymousLogger(); logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) { lines.add(record.getMessage()); }
            @Override public void flush() { }
            @Override public void close() { }
        });
        var stats = new Diagnostics(logger); var player = new PlayerProfile(UUID.randomUUID(), "Miner");
        stats.award(player, "mining", 5, BigDecimal.TEN, BigDecimal.ONE); stats.tick(3_600_000);
        assertTrue(lines.isEmpty());
        stats.configure(true, 3_600_000, 0);
        stats.award(player, "mining", 5, BigDecimal.TEN, BigDecimal.ONE);
        stats.payment(new DataStore.Payment("paid", player.id, player.name, BigDecimal.ONE), BigDecimal.ONE, Map.of("mining", BigDecimal.ONE));
        assertTrue(lines.isEmpty());
        stats.tick(3_600_000);
        assertTrue(lines.stream().anyMatch(line -> line.contains("Job mining") && line.contains("earned=1") && line.contains("capped=9") && line.contains("paid=1") && line.contains("earned/observed-hour=1.00")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("Player Miner")));
        stats.configure(false, 300_000, 3_600_000); int before = lines.size();
        stats.award(player, "fishing", 5, BigDecimal.ONE, BigDecimal.ONE); stats.tick(7_200_000);
        assertEquals(before, lines.size());
    }
}
