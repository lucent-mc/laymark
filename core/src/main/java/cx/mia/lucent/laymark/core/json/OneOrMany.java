package cx.mia.lucent.laymark.core.json;

import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * A list field that also accepts a single bare value.
 *
 * <p>{@code "measure": "RESIDENT_RENDER"} and {@code "measure": ["SPAWN_GENERATION",
 * "RESIDENT_RENDER"]} both read, and each is written back the way it came. Most scenarios name one
 * phase, and making those carry brackets is ceremony; the ones that name several are the reason
 * the field is a list at all.
 *
 * @param <T> the element type, deserialized through the surrounding context so its own adapter
 *     still applies — an unknown enum name still fails strictly here
 */
public final class OneOrMany<T> implements JsonSerializer<List<T>>, JsonDeserializer<List<T>> {

    private final Class<T> element;

    public OneOrMany(Class<T> element) {
        this.element = element;
    }

    @Override
    public List<T> deserialize(JsonElement json, Type type, JsonDeserializationContext context) {
        List<T> values = new ArrayList<>();
        if (json.isJsonArray()) {
            for (JsonElement each : json.getAsJsonArray()) {
                values.add(context.deserialize(each, element));
            }
        } else {
            values.add(context.deserialize(json, element));
        }
        return values;
    }

    @Override
    public JsonElement serialize(List<T> src, Type type, JsonSerializationContext context) {
        // A single value goes back as a bare value, so a config that used the short spelling is
        // not rewritten into the long one the first time anything reads and writes it.
        if (src.size() == 1) {
            return context.serialize(src.get(0), element);
        }
        JsonArray array = new JsonArray();
        src.forEach(value -> array.add(context.serialize(value, element)));
        return array;
    }
}
