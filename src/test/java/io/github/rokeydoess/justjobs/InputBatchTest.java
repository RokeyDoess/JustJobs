package io.github.rokeydoess.justjobs;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class InputBatchTest {
    @Test void explicitContributorsTakePriorityOverTheWorkstationOwner() {
        var batch = new InputBatch(); var fallback = new UUID(2, 1);
        batch.add(FIRST, 2); batch.add(null, 2); batch.add(SECOND, 1);
        assertEquals(FIRST, batch.consume(fallback)); assertEquals(FIRST, batch.consume(fallback));
        assertEquals(fallback, batch.consume(fallback)); assertEquals(fallback, batch.consume(fallback));
        assertEquals(SECOND, batch.consume(fallback)); assertNull(batch.consume(fallback));
    }
    @Test void ineligibleInputsNeverBecomeOwnerFallbackAfterPartialWithdrawal() {
        var batch = new InputBatch(); batch.add(null, 2, false); batch.add(null, 2); batch.resize(3, null);
        assertNull(batch.consume(FIRST)); assertNull(batch.consume(FIRST)); assertEquals(FIRST, batch.consume(FIRST));
        batch.add(null, 3, false); batch.resize(2, null);
        assertNull(batch.consume(FIRST)); assertNull(batch.consume(FIRST));
    }
    private static final UUID FIRST = new UUID(1, 1), SECOND = new UUID(1, 2);
    @Test void topUpsKeepEachPlayersQuantityInOrder() {
        var batch = new InputBatch(); batch.add(FIRST, 32); batch.add(SECOND, 16);
        for (int i = 0; i < 32; i++) assertEquals(FIRST, batch.consume());
        for (int i = 0; i < 16; i++) assertEquals(SECOND, batch.consume());
        assertNull(batch.consume()); assertEquals(0, batch.size());
    }
    @Test void hopperInputsNeverInheritAnEarlierOwner() {
        var batch = new InputBatch(); batch.add(FIRST, 1); batch.resize(3, null);
        assertEquals(FIRST, batch.consume()); assertNull(batch.consume()); assertNull(batch.consume());
        batch.resize(2, SECOND); assertEquals(SECOND, batch.consume()); assertEquals(SECOND, batch.consume());
    }
    @Test void withdrawalsRemoveNewestInputsWithoutMovingOlderClaims() {
        var batch = new InputBatch(); batch.add(FIRST, 10); batch.add(SECOND, 5); batch.resize(12, null);
        assertEquals(List.of(new InputBatch.Part(FIRST, 10), new InputBatch.Part(SECOND, 2)), batch.parts());
        batch.resize(4, null); assertEquals(List.of(new InputBatch.Part(FIRST, 4)), batch.parts());
        batch.resize(0, null); assertNull(batch.consume());
    }
    @Test void adjacentIdenticalOwnersMergeAndSnapshotsStayIndependent() {
        var batch = new InputBatch(); batch.add(FIRST, 1); var snapshot = batch.parts(); batch.add(FIRST, 2);
        assertEquals(1, batch.parts().size()); assertEquals(3, batch.size()); assertEquals(1, snapshot.getFirst().amount());
        assertThrows(IllegalArgumentException.class, () -> batch.resize(-1, null));
    }
}
