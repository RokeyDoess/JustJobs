package io.github.rokeydoess.justjobs;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class InputBatchTest {
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
