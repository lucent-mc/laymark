package cx.mia.lucent.laymark.core.select;

import cx.mia.lucent.laymark.core.plan.ScoreWeights;
import cx.mia.lucent.laymark.core.stats.Comparison;
import java.util.List;
import java.util.Map;

/** Combines independently normalized speed and retained-memory objectives. */
public final class CompositeScore {

    private CompositeScore() {}

    public static double of(
            List<Comparison> speed,
            List<Comparison> memory,
            Map<String, Double> scenarioWeights,
            ScoreWeights objectiveWeights) {
        double total = 0;
        double weight = 0;
        if (objectiveWeights.speed() > 0 && !speed.isEmpty()) {
            total += BandGate.weightedScore(speed, scenarioWeights) * objectiveWeights.speed();
            weight += objectiveWeights.speed();
        }
        if (objectiveWeights.memory() > 0 && !memory.isEmpty()) {
            total += BandGate.weightedScore(memory, scenarioWeights) * objectiveWeights.memory();
            weight += objectiveWeights.memory();
        }
        return weight == 0 ? 0 : total / weight;
    }
}
