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

/**
 * Reads a settings object as namespace → key → value.
 *
 * <p>The shape is open by design: keys are validated by the receiving handler (the {@code
 * minecraft} namespace against the game's own options registry, which names near misses for a
 * typo), not by a list this adapter would have to keep in sync. What the adapter does hold is the
 * shape — a namespace must be an object of primitives, so a settings field accidentally written at
 * the top level ({@code "renderDistance": 24} instead of {@code "minecraft": {"renderDistance":
 * 24}}) fails here with the two-level form named, rather than becoming a namespace nobody
 * handles.
 */
final class PresetAdapter implements JsonSerializer<Preset>, JsonDeserializer<Preset> {

    @Override
    public Preset deserialize(JsonElement json, Type type, JsonDeserializationContext context) {
        if (!json.isJsonObject()) {
            throw new PlanException("a settings object was expected, got " + json);
        }
        JsonObject object = json.getAsJsonObject();
        java.util.Map<String, java.util.Map<String, String>> parsed =
                new java.util.LinkedHashMap<>();
        for (String namespace : object.keySet()) {
            JsonElement entries = object.get(namespace);
            if (!entries.isJsonObject()) {
                throw new PlanException(
                        "settings are written as namespace -> key -> value, so '" + namespace
                                + "' must be an object — e.g. \"minecraft\": { \"" + namespace
                                + "\": " + entries + " }");
            }
            java.util.Map<String, String> values = new java.util.LinkedHashMap<>();
            for (String key : entries.getAsJsonObject().keySet()) {
                JsonElement value = entries.getAsJsonObject().get(key);
                if (!value.isJsonPrimitive()) {
                    // Values stay the literal the operator wrote. Typing is the receiving
                    // option's job -- its own codec parses the literal, so an unacceptable value
                    // fails there with the option named, not here with a shape complaint.
                    throw new PlanException(
                            "settings." + namespace + "." + key
                                    + " must be a string, number or boolean");
                }
                values.put(key, value.getAsString());
            }
            parsed.put(namespace, values);
        }
        return new Preset(parsed);
    }

    @Override
    public JsonElement serialize(Preset src, Type type, JsonSerializationContext context) {
        JsonObject namespaces = new JsonObject();
        src.values()
                .forEach(
                        (namespace, values) -> {
                            JsonObject entries = new JsonObject();
                            values.forEach(entries::addProperty);
                            namespaces.add(namespace, entries);
                        });
        return namespaces;
    }
}
