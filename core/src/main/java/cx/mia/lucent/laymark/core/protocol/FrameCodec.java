package cx.mia.lucent.laymark.core.protocol;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
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

    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

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
        JsonObject object;
        try {
            object = JsonParser.parseString(line).getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            throw new ProtocolException("frame is not a JSON object: " + abbreviate(line), e);
        }
        if (!object.has(TYPE)) {
            throw new ProtocolException("frame has no '" + TYPE + "': " + abbreviate(line));
        }
        String name = object.get(TYPE).getAsString();
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
