package cx.mia.lucent.laymark.core.scenario;

import cx.mia.lucent.laymark.core.harness.Preset;
import cx.mia.lucent.laymark.core.plan.PlanException;
import cx.mia.lucent.laymark.core.plan.StopCondition;

/**
 * A stop condition as authored, where the target may be a number or a name for one.
 *
 * <p>The names exist so a target stays correct when the preset changes. An operator who writes
 * {@code 289} and later raises render distance from 8 to 12 now has a scenario that stops a third
 * of the way through the work it used to do, and nothing says so.
 *
 * <pre>
 *   "stop": { "kind": "time",   "target": 30000,           "timeout": 60000 }
 *   "stop": { "kind": "chunks", "target": 2048,            "timeout": 120000 }
 *   "stop": { "kind": "chunks", "target": "all-in-radius", "timeout": 120000 }
 * </pre>
 *
 * <p><strong>Only names derived from pinned configuration are allowed.</strong> {@code
 * all-in-radius} comes from render distance, which the preset fixes identically for both arms.
 * A name like "all in view" would come from what the renderer considers visible — which is the
 * thing a culling mod changes, so the arm under test would complete having loaded fewer chunks and
 * post a better time for doing less work. That is the same bias as stopping on a plateau, and it
 * is why no such name exists.
 */
public record StopSpec(StopCondition.Kind kind, TargetRef target, Long timeout) {

    public StopSpec {
        if (kind == null) {
            throw new PlanException("a stop condition has no kind");
        }
        if (target == null) {
            throw new PlanException("a stop condition has no target");
        }
    }

    /**
     * Expands the target against the settings this scenario resolved to.
     *
     * <p>Against the <em>requested</em> preset, not the effective one, and deliberately: both arms
     * must be asked for the same amount of work. If the game declines the render distance, that is
     * a readback deviation and gets flagged as one — it must not quietly change how much work the
     * scenario demands, because then the arms would no longer be doing the same thing.
     */
    public StopCondition resolve(Preset preset) {
        return new StopCondition(kind, target.count(kind, preset), timeout == null ? 0 : timeout);
    }

    public static StopSpec of(StopCondition.Kind kind, long target, long timeout) {
        return new StopSpec(kind, new TargetRef.Count(target), timeout);
    }
}
