package cx.mia.lucent.laymark.core.scenario;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import cx.mia.lucent.laymark.core.plan.PlanException;
import java.lang.reflect.Type;

/**
 * Reads a stop target as either a number or a name, and writes back the one it read.
 *
 * <p>Same trick as {@code settings}: the JSON type is already the discriminator, so an operator
 * never writes a tag that exists only for the parser.
 */
final class TargetRefAdapter implements JsonSerializer<TargetRef>, JsonDeserializer<TargetRef> {

    @Override
    public TargetRef deserialize(JsonElement json, Type type, JsonDeserializationContext context) {
        if (json.isJsonPrimitive() && json.getAsJsonPrimitive().isNumber()) {
            return TargetRef.of(json.getAsLong());
        }
        if (json.isJsonPrimitive() && json.getAsJsonPrimitive().isString()) {
            String name = json.getAsString();
            if (TargetRef.AllInRadius.NAME.equals(name)) {
                return TargetRef.allInRadius();
            }
            throw new PlanException(
                    "unknown stop target '" + name + "'; known names are ["
                            + TargetRef.AllInRadius.NAME
                            + "], or write a number");
        }
        throw new PlanException("'target' must be a number or a name, got " + json);
    }

    @Override
    public JsonElement serialize(TargetRef src, Type type, JsonSerializationContext context) {
        return switch (src) {
            case TargetRef.Count count -> new JsonPrimitive(count.value());
            case TargetRef.AllInRadius unused -> new JsonPrimitive(TargetRef.AllInRadius.NAME);
        };
    }
}
