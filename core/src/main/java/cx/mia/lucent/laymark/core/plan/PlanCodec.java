package cx.mia.lucent.laymark.core.plan;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import cx.mia.lucent.laymark.core.harness.HarnessException;
import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads and writes {@link RunPlan} as JSON.
 *
 * <p>The plan is a <strong>file on disk</strong> in {@code config/laymark/}: the runner writes
 * it, the harness reads it, and it is archived with the results because a historical result is
 * only interpretable alongside the plan that produced it. That makes round-tripping the whole
 * point of this type, not an incidental capability.
 *
 * <p>Two things reflection alone gets wrong here, both of which fail at read time rather than
 * write time — the worst place for a benchmark to discover a problem:
 *
 * <ol>
 *   <li>{@link StopCondition} is a sealed interface. Reflection writes the variant's fields with
 *       no discriminator and then cannot decide what to build. So variants carry an explicit
 *       {@code kind}, dispatched through a registry, the same shape {@code FrameCodec} uses.
 *   <li>Gson invokes a record's canonical constructor but rewraps whatever it throws. A plan
 *       whose scenarios contain a cycle would surface as a bare runtime exception rather than a
 *       {@link PlanException}, slipping past every {@code catch (PlanException)} the callers are
 *       written around. Validation failures are unwrapped back to what they were.
 * </ol>
 */
public final class PlanCodec {

    private PlanCodec() {}

    private static final String KIND = "kind";

    private static final Map<String, Class<? extends StopCondition>> BY_NAME =
            new LinkedHashMap<>();
    private static final Map<Class<? extends StopCondition>, String> BY_CLASS =
            new LinkedHashMap<>();

    private static void register(String name, Class<? extends StopCondition> type) {
        BY_NAME.put(name, type);
        BY_CLASS.put(type, name);
    }

    static {
        register("fixed-duration", StopCondition.FixedDuration.class);
        register("until-complete", StopCondition.UntilComplete.class);
    }

    /** Every registered discriminator, so a test can assert the registry covers the hierarchy. */
    public static java.util.Set<String> registeredKinds() {
        return java.util.Set.copyOf(BY_NAME.keySet());
    }

    /**
     * The stop-condition adapter, shared with the scenario config codec.
     *
     * <p>Shared rather than duplicated so the shape an operator writes in a config is exactly the
     * shape that gets archived in the plan beside their results. Two registries would be two
     * chances for those to drift.
     */
    public static Object stopConditionAdapter() {
        return new StopConditionAdapter();
    }

    // registerTypeAdapter, not registerTypeHierarchyAdapter. A hierarchy adapter also claims the
    // concrete variants, so the adapter's own context.serialize(src, src.getClass()) re-enters it
    // and recurses until the stack dies. Binding to the interface alone lets the variant fall
    // through to Gson's reflective record handling, which is the whole point of the delegation.
    private static final Gson GSON =
            new GsonBuilder()
                    .setPrettyPrinting()
                    .disableHtmlEscaping()
                    .registerTypeAdapter(StopCondition.class, new StopConditionAdapter())
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

    private static final class StopConditionAdapter
            implements JsonSerializer<StopCondition>, JsonDeserializer<StopCondition> {

        @Override
        public JsonElement serialize(
                StopCondition src, Type type, JsonSerializationContext context) {
            String name = BY_CLASS.get(src.getClass());
            if (name == null) {
                throw new PlanException(
                        "unregistered stop condition " + src.getClass().getName()
                                + "; add it to PlanCodec's registry");
            }
            JsonObject out = new JsonObject();
            out.addProperty(KIND, name);
            context.serialize(src, src.getClass())
                    .getAsJsonObject()
                    .entrySet()
                    .forEach(e -> out.add(e.getKey(), e.getValue()));
            return out;
        }

        @Override
        public StopCondition deserialize(
                JsonElement json, Type type, JsonDeserializationContext context) {
            if (!json.isJsonObject()) {
                throw new PlanException("stop condition must be an object, got " + json);
            }
            JsonElement kind = json.getAsJsonObject().get(KIND);
            if (kind == null || !kind.isJsonPrimitive() || !kind.getAsJsonPrimitive().isString()) {
                throw new PlanException(
                        "stop condition has no string '" + KIND + "'; known kinds are "
                                + BY_NAME.keySet());
            }
            Class<? extends StopCondition> variant = BY_NAME.get(kind.getAsString());
            if (variant == null) {
                throw new PlanException(
                        "unknown stop condition '" + kind.getAsString() + "'; known kinds are "
                                + BY_NAME.keySet());
            }
            return context.deserialize(json, variant);
        }
    }
}
