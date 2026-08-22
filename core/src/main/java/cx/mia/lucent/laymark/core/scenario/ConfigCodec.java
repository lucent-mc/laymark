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
                    .registerTypeAdapter(Preset.class, new PresetAdapter())
                    .registerTypeAdapter(PresetRef.class, new PresetRefAdapter())
                    .create();

    public static String write(ScenarioConfig config) {
        return GSON.toJson(config);
    }

    /**
     * Reads a config, comments and trailing commas included.
     *
     * <p>{@code //} and <code>/* *&#47;</code> comments and trailing commas are accepted — the
     * config is hand-authored, a schema someone can annotate in place is a schema they can
     * actually learn, and a trailing comma is how an editable list stays editable. Gson's lenient
     * mode covers the comments but not the commas, so both are stripped by a string-aware pass
     * first (newlines preserved, so parse errors still name the right line). The archived plan is
     * still written as strict JSON; leniency is for what humans write, not what Laymark records.
     *
     * @throws PlanException if the document is malformed or describes an invalid config
     */
    public static ScenarioConfig read(String json) {
        if (json == null || json.isBlank()) {
            throw new PlanException("scenario config is empty");
        }
        try {
            var reader =
                    new com.google.gson.stream.JsonReader(new java.io.StringReader(strip(json)));
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
     * Strips comments and trailing commas, leaving strings untouched and newlines in place.
     *
     * <p>Not a general JSONC parser — just the two shapes hand-written configs contain that Gson
     * refuses. A {@code //} inside a quoted string (a Windows path, a URL) survives, because the
     * scan tracks string state rather than pattern-matching.
     */
    private static String strip(String text) {
        StringBuilder noComments = new StringBuilder(text.length());
        boolean inString = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                noComments.append(c);
                if (c == '\\' && i + 1 < text.length()) {
                    noComments.append(text.charAt(++i));
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                noComments.append(c);
                continue;
            }
            if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '/') {
                while (i < text.length() && text.charAt(i) != '\n') {
                    i++;
                }
                noComments.append('\n');
                continue;
            }
            if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < text.length()
                        && !(text.charAt(i) == '*' && text.charAt(i + 1) == '/')) {
                    if (text.charAt(i) == '\n') {
                        noComments.append('\n');
                    }
                    i++;
                }
                i++;
                continue;
            }
            noComments.append(c);
        }

        StringBuilder result = new StringBuilder(noComments.length());
        inString = false;
        for (int i = 0; i < noComments.length(); i++) {
            char c = noComments.charAt(i);
            if (inString) {
                result.append(c);
                if (c == '\\' && i + 1 < noComments.length()) {
                    result.append(noComments.charAt(++i));
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                result.append(c);
                continue;
            }
            if (c == ',') {
                int next = i + 1;
                while (next < noComments.length()
                        && Character.isWhitespace(noComments.charAt(next))) {
                    next++;
                }
                if (next < noComments.length()
                        && (noComments.charAt(next) == '}' || noComments.charAt(next) == ']')) {
                    continue; // a trailing comma; the closer follows directly
                }
            }
            result.append(c);
        }
        return result.toString();
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
