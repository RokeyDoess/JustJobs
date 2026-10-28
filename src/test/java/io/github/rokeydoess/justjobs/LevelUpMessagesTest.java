package io.github.rokeydoess.justjobs;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class LevelUpMessagesTest {
    private static final UUID FIRST = new UUID(1, 1), SECOND = new UUID(1, 2);
    private final LevelUpMessages messages = new LevelUpMessages();
    private final List<LevelUpMessages.Notice> sent = new ArrayList<>();
    private void offer(UUID player, String job, int level) { messages.offer(player, new LevelUpMessages.Notice(job, job, level)); }

    @Test void firstMessageIsImmediateAndRapidGainsCollapseToTheLatestLevel() {
        offer(FIRST, "cooking", 2); messages.flush(FIRST, 1000, 10_000, sent::add);
        for (int level = 3; level <= 15; level++) {
            offer(FIRST, "cooking", level); messages.flush(FIRST, 1000 + level, 10_000, sent::add);
        }
        assertEquals(List.of(2), sent.stream().map(LevelUpMessages.Notice::level).toList());
        messages.flush(FIRST, 10_999, 10_000, sent::add); assertEquals(1, sent.size());
        messages.flush(FIRST, 11_000, 10_000, sent::add);
        assertEquals(List.of(2, 15), sent.stream().map(LevelUpMessages.Notice::level).toList());
        assertTrue(messages.pendingPlayers().isEmpty());
    }
    @Test void jobsShareThePlayersSpacingWithoutStarvingOtherJobs() {
        offer(FIRST, "mining", 2); messages.flush(FIRST, 0, 10_000, sent::add);
        offer(FIRST, "cooking", 2); offer(FIRST, "smithing", 3); offer(FIRST, "cooking", 8);
        messages.flush(FIRST, 10_000, 10_000, sent::add); offer(FIRST, "cooking", 9);
        messages.flush(FIRST, 20_000, 10_000, sent::add); messages.flush(FIRST, 30_000, 10_000, sent::add);
        assertEquals(List.of("mining", "cooking", "smithing", "cooking"), sent.stream().map(LevelUpMessages.Notice::job).toList());
        assertEquals(List.of(2, 8, 3, 9), sent.stream().map(LevelUpMessages.Notice::level).toList());
    }
    @Test void playersHaveIndependentTimingAndZeroRestoresImmediateMessages() {
        offer(FIRST, "cooking", 2); offer(SECOND, "cooking", 2);
        messages.flush(FIRST, 1, 10_000, sent::add); messages.flush(SECOND, 1, 10_000, sent::add); assertEquals(2, sent.size());
        offer(FIRST, "cooking", 3); offer(FIRST, "smithing", 4); messages.flush(FIRST, 1, 0, sent::add);
        assertEquals(4, sent.size()); assertTrue(messages.pendingPlayers().isEmpty());
    }
    @Test void leavingOrDisablingMessagesDiscardsPendingNotices() {
        offer(FIRST, "cooking", 2); offer(SECOND, "smithing", 2); messages.forget(FIRST);
        messages.flush(FIRST, 10_000, 10_000, sent::add); assertTrue(sent.isEmpty());
        assertEquals(java.util.Set.of(SECOND), messages.pendingPlayers());
        messages.clear(); assertTrue(messages.pendingPlayers().isEmpty());
        messages.flush(SECOND, 10_000, 10_000, sent::add); assertTrue(sent.isEmpty());
    }
}
