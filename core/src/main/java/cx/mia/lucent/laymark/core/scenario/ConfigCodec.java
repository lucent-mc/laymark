package cx.mia.lucent.laymark.core.scenario;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import cx.mia.lucent.laymark.core.Phase;
import cx.mia.lucent.laymark.core.harness.HarnessException;
import cx.mia.lucent.laymark.core.harness.Preset;
import cx.mia.lucent.laymark.core.json.OneOrMany;
import cx.mia.lucent.laymark.core.json.StrictEnum;
import java.util.List;
import cx.mia.lucent.laymark.core.plan.StopCondition;
import cx.mia.lucent.laymark.core.plan.PlanException;

/**
 * Reads and writes {@link ScenarioConfig} as JSON.
 *
 * <p>This is the file a human edits, which changes what the codec owes them. A plan that fails to
 * parse is a bug; a config that fails to parse is usually a typo, so every error names the
 * scenario and, where it can, what the valid options were.
 *
 * <p>Shares {@code StopCondition}'s discriminator handling with {@link PlanCodec} rather than
 * redefining it, so the shape an operator writes is the shape that gets archived.
 */
public final class ConfigCodec {

    private ConfigCodec() {}

    // Every enum goes through the strict adapter. Gson answers null for a name it does not know,
    // and because every field here has a defaulting rule, that silence would turn a typo into a
    // different-but-valid run: "phase": "RESIDNT_RENDER" would measure the default phase and
    // report success.
    private static final Gson GSON =
            new GsonBuilder()
                    .setPrettyPrinting()
                    .disableHtmlEscaping()
                    .registerTypeAdapter(
                            StopCondition.Kind.class, StrictEnum.of(StopCondition.Kind.class))
                    .registerTypeAdapter(TargetRef.class, new TargetRefAdapter())
                    .registerTypeAdapter(
                            new com.google.gson.reflect.TypeToken<List<Phase>>() {}.getType(),
                            new OneOrMany<>(Phase.class))
                    .registerTypeAdapter(Phase.class, StrictEnum.of(Phase.class))
                    .registerTypeAdapter(
                            Preset.ParticleDetail.class, StrictEnum.of(Preset.ParticleDetail.class))
                    .registerTypeAdapter(
                            Preset.CloudDetail.class, StrictEnum.of(Preset.CloudDetail.class))
                    .registerTypeAdapter(Preset.class, new PresetAdapter())
                    .registerTypeAdapter(PresetRef.class, new PresetRefAdapter())
                    .create();

    public static String write(ScenarioConfig config) {
        return GSON.toJson(config);
    }

    /**
     * Reads a config, comments included.
     *
     * <p>{@code //} and <code>/* *&#47;</code> comments are accepted — the config is hand-authored
     * and a schema someone can annotate in place is a schema they can actually learn. The archived
     * plan is still written as strict JSON; leniency is for what humans write, not what Laymark
     * records.
     *
     * @throws PlanException if the document is malformed or describes an invalid config
     */
    public static ScenarioConfig read(String json) {
        if (json == null || json.isBlank()) {
            throw new PlanException("scenario config is empty");
        }
        try {
            var reader = new com.google.gson.stream.JsonReader(new java.io.StringReader(json));
            reader.setStrictness(com.google.gson.Strictness.LENIENT);
            ScenarioConfig config = GSON.fromJson(reader, ScenarioConfig.class);
            if (config == null) {
                throw new PlanException("scenario config is empty");
            }
            return config;
        } catch (JsonParseException | IllegalStateException e) {
            throw unwrap(e, "scenario config is not valid JSON");
        } catch (RuntimeException e) {
            throw unwrap(e, "scenario config could not be read");
        }
    }

    /**
     * Recovers the validation failure Gson rewraps on its way out, so a config rejected on disk
     * reports the same thing as the identical config built in code.
     */
    private static PlanException unwrap(RuntimeException thrown, String fallback) {
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            if (t instanceof PlanException planException) {
                return planException;
            }
            // Presets and poses validate themselves and report as harness failures. At read time
            // that is a malformed config, not a failed run.
            if (t instanceof HarnessException harnessException) {
                return new PlanException(harnessException.getMessage(), harnessException);
            }
        }
        return new PlanException(fallback + ": " + thrown.getMessage());
    }
}
