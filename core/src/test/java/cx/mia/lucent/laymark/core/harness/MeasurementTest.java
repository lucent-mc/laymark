package cx.mia.lucent.laymark.core.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class MeasurementTest {

    @Test
    void timePerChunkUsesPoseLocalCompletionInsteadOfGlobalOccupancyDelta() {
        Measurement measurement =
                new Measurement(
                        List.of(FrameSample.interval(0, 100_000_000)),
                        List.of(),
                        null,
                        new WorkCounters(0, 1000, 0),
                        new WorkCounters(0, 1001, 0),
                        null,
                        null,
                        100L);

        assertEquals(1.0, measurement.millisPerChunkReceived(), 0.0001);
    }
}
