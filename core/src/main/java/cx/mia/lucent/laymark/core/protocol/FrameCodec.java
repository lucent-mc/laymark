package cx.mia.lucent.laymark.core.protocol;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import cx.mia.lucent.laymark.core.Phase;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Newline-delimited JSON codec for {@link Frame}.
 *
 * <p>One frame per line. Safe without length prefixes because JSON encoders escape newlines
 * inside strings, and the payoff is that the wire format <em>is</em> the durable format: the
 * runner can append a received line straight into {@code events.jsonl} with no translation
 * layer, which keeps crash forensics with the process that survives.
 *
 * <p>Dispatch is by an explicit {@code type} discriminator and an explicit registry, not by
 * reflection over the sealed hierarchy. That means adding a frame type without registering it is
 * a compile-time-adjacent mistake caught by the round-trip test, rather than something that
 * silently serializes and fails to parse on the other side.
 */
public final class FrameCodec {

    private FrameCodec() {}

    private static final String TYPE = "type";

    // HTML escaping stays off: the durable format is read by an operator diagnosing a run, and
    // an escaped `renderDistance=12` is not greppable. Nothing here reaches a web page.
    // Enums go through a strict adapter, because Gson's built-in one answers null for a name it
    // does not know -- indistinguishable downstream from a field the harness never set.
    //
    // This covers a wrong *name* only. An explicit JSON null and an absent member both still
    // decode to null: Gson short-circuits the first before any adapter runs and never consults
    // one for the second. Rejecting those is required-field validation, which belongs with the
    // runner's handshake checks rather than here -- see Frame.Hello.
    private static final Gson GSON =
            new GsonBuilder()
                    .serializeNulls()
                    .disableHtmlEscaping()
                    .registerTypeAdapter(Phase.class, strictEnum(Phase.class, Phase.values()))
                    .create();

    private static <E extends Enum<E>> JsonDeserializer<E> strictEnum(Class<E> type, E[] values) {
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
            throw new JsonParseException(
                    "unknown " + type.getSimpleName() + " '" + name + "'; known values are "
                            + Arrays.toString(values));
        };
    }

    private static final Map<String, Class<? extends Frame>> BY_NAME = new LinkedHashMap<>();
    private static final Map<Class<? extends Frame>, String> BY_CLASS = new LinkedHashMap<>();

    private static void register(String name, Class<? extends Frame> type) {
        BY_NAME.put(name, type);
        BY_CLASS.put(type, name);
    }

    static {
        register("hello", Frame.Hello.class);
        register("heartbeat", Frame.Heartbeat.class);
        register("scenario-started", Frame.ScenarioStarted.class);
        register("phase-entered", Frame.PhaseEntered.class);
        register("scenario-finished", Frame.ScenarioFinished.class);
        register("scenario-measured", Frame.ScenarioMeasured.class);
        register("run-failed", Frame.RunFailed.class);
        register("run-finished", Frame.RunFinished.class);
    }

    /** Every registered discriminator, for tests that assert the registry covers the hierarchy. */
    public static java.util.Set<String> registeredTypes() {
        return java.util.Set.copyOf(BY_NAME.keySet());
    }

    /** @return a single line with no trailing newline */
    public static String encode(Frame frame) {
        String name = BY_CLASS.get(frame.getClass());
        if (name == null) {
            throw new ProtocolException(
                    "unregistered frame type " + frame.getClass().getName()
                            + "; add it to FrameCodec's registry");
        }
        JsonObject object = GSON.toJsonTree(frame).getAsJsonObject();
        // Discriminator first, so a truncated line is still diagnosable by eye.
        JsonObject out = new JsonObject();
        out.addProperty(TYPE, name);
        object.entrySet().forEach(e -> out.add(e.getKey(), e.getValue()));
        return GSON.toJson(out);
    }

    public static Frame decode(String line) {
        if (line == null) {
            throw new ProtocolException("frame is not a JSON object: " + abbreviate(line));
        }
        JsonObject object;
        try {
            object = JsonParser.parseString(line).getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            throw new ProtocolException("frame is not a JSON object: " + abbreviate(line), e);
        }
        // A discriminator that is null, an object or an array satisfies has() and then throws
        // out of getAsString(); every malformed line has to leave here as a ProtocolException.
        JsonElement discriminator = object.get(TYPE);
        if (discriminator == null
                || !discriminator.isJsonPrimitive()
                || !discriminator.getAsJsonPrimitive().isString()) {
            throw new ProtocolException(
                    "frame has no string '" + TYPE + "': " + abbreviate(line));
        }
        String name = discriminator.getAsString();
        Class<? extends Frame> type = BY_NAME.get(name);
        if (type == null) {
            throw new ProtocolException(
                    "unknown frame type '" + name + "'; known types are " + BY_NAME.keySet());
        }
        try {
            return GSON.fromJson(object, type);
        } catch (JsonParseException e) {
            throw new ProtocolException("malformed " + name + " frame: " + abbreviate(line), e);
        }
    }

    private static String abbreviate(String line) {
        if (line == null) {
            return "<null>";
        }
        String trimmed = line.strip();
        return trimmed.length() <= 200 ? trimmed : trimmed.substring(0, 200) + "...";
    }
}
