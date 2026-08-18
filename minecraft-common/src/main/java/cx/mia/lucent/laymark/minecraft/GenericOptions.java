package cx.mia.lucent.laymark.minecraft;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import cx.mia.lucent.laymark.core.harness.HarnessException;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;

/**
 * Any vanilla option by its {@code options.txt} key, set and read through the option's own codec.
 *
 * <p>The registry is the game's, not a hand-kept list: {@code Options.processOptions} visits every
 * {@link OptionInstance} with the exact key it serialises under, so whatever exists in this build
 * — {@code graphicsPreset}, {@code gamma}, {@code cutoutLeaves} — is reachable, and a key that
 * does not exist fails naming the near misses instead of quietly running a different
 * configuration. Values are parsed by the option's codec, so an unacceptable value fails naming
 * the option rather than being silently replaced by the default, which is vanilla's own habit.
 *
 * <p>Reached by reflection because {@code processOptions} is private. The traversal is the same
 * one the game runs to load and save its own options file, which is what makes leaning on it
 * safer than any list Laymark could maintain: a renamed or removed option changes this map in
 * lockstep with the game.
 *
 * <p>Client thread, like everything touching {@link Options}.
 */
final class GenericOptions {

    private GenericOptions() {}

    private static Map<String, OptionInstance<?>> registry;

    private static Map<String, OptionInstance<?>> registry(Options options) {
        if (registry != null) {
            return registry;
        }
        Map<String, OptionInstance<?>> collected = new LinkedHashMap<>();
        Options.FieldAccess collector =
                new Options.FieldAccess() {
                    @Override
                    public <T> void process(String name, OptionInstance<T> option) {
                        collected.put(name, option);
                    }

                    @Override
                    public int process(String name, int value) {
                        return value; // a plain field, not settable through an instance
                    }

                    @Override
                    public boolean process(String name, boolean value) {
                        return value;
                    }

                    @Override
                    public String process(String name, String value) {
                        return value;
                    }

                    @Override
                    public float process(String name, float value) {
                        return value;
                    }

                    @Override
                    public <T> T process(
                            String name, T value, Function<String, T> reader, Function<T, String> writer) {
                        return value;
                    }
                };
        try {
            Method processOptions =
                    Options.class.getDeclaredMethod("processOptions", Options.FieldAccess.class);
            processOptions.setAccessible(true);
            processOptions.invoke(options, collector);
        } catch (ReflectiveOperationException e) {
            throw new HarnessException(
                    "could not enumerate the game's options; the free-form settings namespace"
                            + " cannot work on this build: " + e);
        }
        registry = collected;
        return registry;
    }

    /** Sets one option from the literal the operator wrote, through the option's codec. */
    @SuppressWarnings("unchecked")
    static void set(Options options, String key, String literal) {
        OptionInstance<Object> option = (OptionInstance<Object>) lookup(options, key);
        Object value =
                option.codec()
                        .parse(JsonOps.INSTANCE, literalToJson(literal))
                        .getOrThrow(
                                message ->
                                        new HarnessException(
                                                "option '" + key + "' rejected value '" + literal
                                                        + "': " + message));
        option.set(value);
    }

    /** The option's current value, serialised back through its codec to the same literal form. */
    static String read(Options options, String key) {
        return encode(lookup(options, key), key);
    }

    @SuppressWarnings("unchecked")
    private static <T> String encode(OptionInstance<T> option, String key) {
        JsonElement encoded =
                option.codec()
                        .encodeStart(JsonOps.INSTANCE, option.get())
                        .getOrThrow(
                                message ->
                                        new HarnessException(
                                                "option '" + key + "' could not be read back: "
                                                        + message));
        return encoded.isJsonPrimitive() ? encoded.getAsString() : encoded.toString();
    }

    private static OptionInstance<?> lookup(Options options, String key) {
        OptionInstance<?> option = registry(options).get(key);
        if (option == null) {
            throw new HarnessException(
                    "no vanilla option named '" + key + "'; near misses: "
                            + registry(options).keySet().stream()
                                    .filter(name ->
                                            name.toLowerCase(java.util.Locale.ROOT)
                                                    .contains(
                                                            key.toLowerCase(java.util.Locale.ROOT)
                                                                    .substring(
                                                                            0,
                                                                            Math.min(4, key.length()))))
                                    .sorted()
                                    .limit(8)
                                    .toList());
        }
        return option;
    }

    /**
     * The operator's literal as typed JSON: {@code "true"} parses as a boolean, {@code "1.5"} as
     * a number, anything else as a string — matching what each codec expects for its type.
     */
    private static JsonElement literalToJson(String literal) {
        try {
            JsonElement parsed = JsonParser.parseString(literal);
            if (parsed.isJsonPrimitive()) {
                return parsed;
            }
        } catch (RuntimeException notALiteral) {
            // A bare word like "fancy" is not valid JSON; it is a string.
        }
        return new JsonPrimitive(literal);
    }
}
