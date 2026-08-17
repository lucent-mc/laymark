package cx.mia.lucent.laymark.core.json;

import com.google.gson.JsonDeserializer;
import com.google.gson.JsonParseException;
import java.util.Arrays;

/**
 * Enum deserialization that refuses a name it does not recognise.
 *
 * <p>Gson's built-in handling answers {@code null} for an unknown name, which downstream is
 * indistinguishable from a field nobody set. Every document Laymark reads has a defaulting rule
 * for absent fields, so that silence turns a typo into a different-but-valid run: {@code
 * "phase": "RESIDNT_RENDER"} would quietly measure the default phase and report success.
 *
 * <p>This covers a wrong <em>name</em> only. An explicit JSON null and an absent member both still
 * decode to null, because Gson short-circuits the first before any adapter runs and never consults
 * one for the second. Requiring a field is a separate job, done by the type that owns it.
 */
public final class StrictEnum {

    private StrictEnum() {}

    public static <E extends Enum<E>> JsonDeserializer<E> of(Class<E> type) {
        E[] values = type.getEnumConstants();
        return (json, unused, context) -> {
            if (!json.isJsonPrimitive() || !json.getAsJsonPrimitive().isString()) {
                throw new JsonParseException(type.getSimpleName() + " must be a string");
            }
            String name = json.getAsString();
            for (E value : values) {
                if (value.name().equals(name)) {
                    return value;
                }
            }
            // Naming the alternatives is the whole value of failing here rather than at use.
            throw new JsonParseException(
                    "unknown " + type.getSimpleName() + " '" + name + "'; known values are "
                            + Arrays.toString(values));
        };
    }
}
