package io.github.rokeydoess.justjobs;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TravelProgressTest {
    @Test void rewardsOnlyCompletedDistancesAndKeepsTheRemainder() {
        var travel = new TravelProgress();
        assertEquals(0, travel.add("exploring", 249, 250));
        assertEquals(250, travel.add("exploring", 11, 250));
        assertEquals(10, travel.distance("exploring"));
        assertEquals(500, travel.add("exploring", 500, 250));
        assertEquals(10, travel.distance("exploring"));
    }
    @Test void walkingAndSailingAccumulateIndependently() {
        var travel = new TravelProgress();
        assertEquals(250, travel.add("exploring", 250, 250));
        assertEquals(0, travel.add("sailing", 250, 500));
        assertEquals(500, travel.add("sailing", 250, 500));
    }
    @Test void fractionalMovementDoesNotMissAnExactMilestone() {
        var travel = new TravelProgress(); double rewarded = 0;
        for (int i = 0; i < 2500; i++) rewarded += travel.add("exploring", .1, 250);
        assertEquals(250, rewarded);
        assertEquals(0, travel.distance("exploring"), 1e-8);
    }
    @Test void changingIntervalsPreservesPreviouslyTraveledDistance() {
        var travel = new TravelProgress(); travel.add("exploring", 240, 250);
        assertEquals(0, travel.add("exploring", 10, 500));
        assertEquals(250, travel.distance("exploring"));
        assertEquals(200, travel.add("exploring", 1, 100));
        assertEquals(51, travel.distance("exploring"));
    }
    @Test void snapshotsRestoreUnfinishedJourneys() {
        var travel = new TravelProgress(); travel.add("exploring", 249.5, 250);
        var restored = new TravelProgress(); travel.snapshot().forEach(restored::restore);
        assertEquals(250, restored.add("exploring", .5, 250));
        assertTrue(restored.snapshot().isEmpty());
    }
}
