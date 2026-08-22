package cx.mia.lucent.laymark.core.plan;

/** Relative importance of the two independently-normalized benchmark objectives. */
public record ScoreWeights(double speed, double memory) {

    /** Speed and retained heap have equal influence unless the operator says otherwise. */
    public static final ScoreWeights DEFAULT = new ScoreWeights(1.0, 1.0);

    public ScoreWeights {
        if (!Double.isFinite(speed) || speed < 0) {
            throw new PlanException("scoreWeights.speed must be a finite non-negative number");
        }
        if (!Double.isFinite(memory) || memory < 0) {
            throw new PlanException("scoreWeights.memory must be a finite non-negative number");
        }
        if (speed == 0 && memory == 0) {
            throw new PlanException("scoreWeights must enable speed, memory, or both");
        }
    }
}
