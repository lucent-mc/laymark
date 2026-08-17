package cx.mia.lucent.laymark.core.scenario;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import cx.mia.lucent.laymark.core.harness.Preset;
import cx.mia.lucent.laymark.core.plan.PlanException;
import java.lang.reflect.Type;

/**
 * Reads {@link PresetRef} from either spelling and writes back the one it came from.
 *
 * <p>A string is a name, an object is settings. No discriminator, because the JSON type already is
 * one — asking an operator to write {@code {"kind": "named", "name": "near"}} would be a tag for
 * the parser's benefit rather than the reader's.
 */
final class PresetRefAdapter implements JsonSerializer<PresetRef>, JsonDeserializer<PresetRef> {

    @Override
    public PresetRef deserialize(JsonElement json, Type type, JsonDeserializationContext context) {
        if (json.isJsonPrimitive() && json.getAsJsonPrimitive().isString()) {
            return PresetRef.named(json.getAsString());
        }
        if (json.isJsonObject()) {
            return PresetRef.inline(context.deserialize(json, Preset.class));
        }
        throw new PlanException(
                "'settings' must be either a preset name or a settings object, got " + json);
    }

    @Override
    public JsonElement serialize(PresetRef src, Type type, JsonSerializationContext context) {
        return switch (src) {
            case PresetRef.Named named -> new JsonPrimitive(named.name());
            case PresetRef.Inline inline -> context.serialize(inline.preset(), Preset.class);
        };
    }
}
