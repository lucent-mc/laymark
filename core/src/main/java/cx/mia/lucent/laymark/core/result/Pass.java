package cx.mia.lucent.laymark.core.result;

/**
 * Which traversal of the scenario list a result came from (§8.2).
 *
 * <p>One launch runs the whole list twice. The cold pass measures a fresh JVM; the warm pass
 * measures the same sequence behind the JIT, heap growth and GC the cold pass paid for. Both are
 * kept as data — the warmth delta is a result, not a cost — but they are different populations,
 * and pooling them would inflate every interval with a difference that is not the candidate's.
 * Acclimation is not a pass: it is a whole discarded arm.
 */
public enum Pass {
    COLD,
    WARM
}
