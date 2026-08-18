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
                    "fieldOfView",
                    "options");

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
                integer(object, "fieldOfView", defaults.fieldOfView()),
                object.has("options") ? options(object.get("options")) : java.util.Map.of());
    }

    /**
     * The free-form escape hatch: namespace → key → value, values kept as the literal the
     * operator wrote. Typing is the receiving option's job — its own codec parses the literal, so
     * an unacceptable value fails there with the option named, not here with a shape complaint.
     */
    private static java.util.Map<String, java.util.Map<String, String>> options(JsonElement raw) {
        if (!raw.isJsonObject()) {
            throw new PlanException("settings.options must be an object of namespaces");
        }
        java.util.Map<String, java.util.Map<String, String>> parsed =
                new java.util.LinkedHashMap<>();
        for (String namespace : raw.getAsJsonObject().keySet()) {
            JsonElement entries = raw.getAsJsonObject().get(namespace);
            if (!entries.isJsonObject()) {
                throw new PlanException(
                        "settings.options." + namespace + " must be an object of key: value");
            }
            java.util.Map<String, String> values = new java.util.LinkedHashMap<>();
            for (String key : entries.getAsJsonObject().keySet()) {
                JsonElement value = entries.getAsJsonObject().get(key);
                if (!value.isJsonPrimitive()) {
                    throw new PlanException(
                            "settings.options." + namespace + "." + key
                                    + " must be a string, number or boolean");
                }
                values.put(key, value.getAsString());
            }
            parsed.put(namespace, values);
        }
        return parsed;
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
        if (!src.options().isEmpty()) {
            JsonObject namespaces = new JsonObject();
            src.options()
                    .forEach(
                            (namespace, values) -> {
                                JsonObject entries = new JsonObject();
                                values.forEach(entries::addProperty);
                                namespaces.add(namespace, entries);
                            });
            object.add("options", namespaces);
        }
        return object;
    }
}
