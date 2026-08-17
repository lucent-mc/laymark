package cx.mia.lucent.laymark.core.plan;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import cx.mia.lucent.laymark.core.harness.HarnessException;
import cx.mia.lucent.laymark.core.json.StrictEnum;

/**
 * Reads and writes {@link RunPlan} as JSON.
 *
 * <p>The plan is a <strong>file on disk</strong> in {@code config/laymark/}: the runner writes
 * it, the harness reads it, and it is archived with the results because a historical result is
 * only interpretable alongside the plan that produced it. That makes round-tripping the whole
 * point of this type, not an incidental capability.
 *
" * <p>One thing reflection gets wrong here, and it fails at read time rather than write time --
 * the worst place for a benchmark to discover a problem: Gson invokes a record's canonical constructor but rewraps whatever it throws. A plan
 *       whose scenarios contain a cycle would surface as a bare runtime exception rather than a
 *       {@link PlanException}, slipping past every {@code catch (PlanException)} the callers are
 *  written around. Validation failures are unwrapped back to what they were.
 */
public final class PlanCodec {

    private PlanCodec() {}

    // A stop condition is one flat record with an enum kind, so reflection handles it. It used to
    // be a sealed hierarchy, which needed a discriminator, a hand-kept registry and a custom
    // adapter to express what turned out to be a difference of unit.
    private static final Gson GSON =
            new GsonBuilder()
                    .setPrettyPrinting()
                    .disableHtmlEscaping()
                    .registerTypeAdapter(
                            StopCondition.Kind.class, StrictEnum.of(StopCondition.Kind.class))
                    .create();

    /** Pretty-printed, because a human diagnosing a run reads this file. */
    public static String write(RunPlan plan) {
        return GSON.toJson(plan);
    }

    /**
     * @throws PlanException if the document is malformed or describes an invalid plan
     */
    public static RunPlan read(String json) {
        if (json == null || json.isBlank()) {
            throw new PlanException("plan document is empty");
        }
        try {
            RunPlan plan = GSON.fromJson(json, RunPlan.class);
            if (plan == null) {
                throw new PlanException("plan document is empty");
            }
            return plan;
        } catch (JsonParseException e) {
            throw unwrap(e, "plan document is not valid JSON");
        } catch (RuntimeException e) {
            throw unwrap(e, "plan document could not be read");
        }
    }

    /**
     * Recovers a {@link PlanException} thrown by a record's compact constructor, which Gson
     * rewraps on its way out. Without this a plan loaded from disk reports a different exception
     * type than the identical plan constructed in code.
     */
    private static PlanException unwrap(RuntimeException thrown, String fallback) {
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            if (t instanceof PlanException planException) {
                return planException;
            }
            // A scenario embeds a preset and a pose, which validate themselves and report as
            // harness failures. Reaching a caller that way would be correct about the cause and
            // wrong about the context: at read time this is a malformed plan, not a failed run.
            if (t instanceof HarnessException harnessException) {
                return new PlanException(harnessException.getMessage(), harnessException);
            }
        }
        return new PlanException(fallback + ": " + thrown.getMessage());
    }

}
