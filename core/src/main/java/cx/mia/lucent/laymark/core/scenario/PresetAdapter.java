package cx.mia.lucent.laymark.core.scenario;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import cx.mia.lucent.laymark.core.harness.Preset;
import cx.mia.lucent.laymark.core.plan.PlanException;
import java.lang.reflect.Type;
import java.util.Set;

/**
 * Reads a preset field by field, so a config may name only the settings it cares about.
 *
 * <p>Gson builds records by filling absent components with zero, which for a preset means render
 * distance 0 and a null particle setting — values the constructor correctly rejects. So without
 * this, "leave a field out and the default applies" was documented and impossible: every preset had
 * to spell out all seven settings, and a config that said {@code {"renderDistance": 24}} failed
 * complaining about a field of view nobody wrote.
 *
 * <p>Unknown keys are still refused. Defaulting rules and silent typos together turn
 * {@code "renderDistanc": 24} into a run at 12 that reports success.
 */
final class PresetAdapter implements JsonSerializer<Preset>, JsonDeserializer<Preset> {

    private static final Set<String> FIELDS =
            Set.of(
                    "renderDistance",
                    "simulationDistance",
                    "particles",
                    "clouds",
                    "entityShadows",
                    "biomeBlendRadius",
                    "fieldOfView");

    @Override
    public Preset deserialize(JsonElement json, Type type, JsonDeserializationContext context) {
        if (!json.isJsonObject()) {
            throw new PlanException("a settings object was expected, got " + json);
        }
        JsonObject object = json.getAsJsonObject();
        for (String key : object.keySet()) {
            if (!FIELDS.contains(key)) {
                throw new PlanException(
                        "settings has an unknown field '" + key + "'; known fields are " + FIELDS);
            }
        }

        Preset defaults = Preset.defaults();
        return new Preset(
                integer(object, "renderDistance", defaults.renderDistance()),
                integer(object, "simulationDistance", defaults.simulationDistance()),
                object.has("particles")
                        ? context.deserialize(object.get("particles"), Preset.ParticleDetail.class)
                        : defaults.particles(),
                object.has("clouds")
                        ? context.deserialize(object.get("clouds"), Preset.CloudDetail.class)
                        : defaults.clouds(),
                object.has("entityShadows")
                        ? object.get("entityShadows").getAsBoolean()
                        : defaults.entityShadows(),
                integer(object, "biomeBlendRadius", defaults.biomeBlendRadius()),
                integer(object, "fieldOfView", defaults.fieldOfView()));
    }

    private static int integer(JsonObject object, String field, int fallback) {
        return object.has(field) ? object.get(field).getAsInt() : fallback;
    }

    /**
     * Written out in full, unlike the way it may be read.
     *
     * <p>A plan is archived beside the results it produced, so it records every setting that was in
     * force rather than the subset someone chose to type.
     */
    @Override
    public JsonElement serialize(Preset src, Type type, JsonSerializationContext context) {
        JsonObject object = new JsonObject();
        object.addProperty("renderDistance", src.renderDistance());
        object.addProperty("simulationDistance", src.simulationDistance());
        object.add("particles", context.serialize(src.particles()));
        object.add("clouds", context.serialize(src.clouds()));
        object.addProperty("entityShadows", src.entityShadows());
        object.addProperty("biomeBlendRadius", src.biomeBlendRadius());
        object.addProperty("fieldOfView", src.fieldOfView());
        return object;
    }
}
