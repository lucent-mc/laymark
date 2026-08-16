package cx.mia.lucent.laymark.runner.launch;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;

/**
 * The launcher's on-disk version JSON, parsed to the parts a launch needs.
 *
 * <p><strong>This is not a complete launch descriptor.</strong> Established by experiment: the
 * launcher wraps the launch with its own agent and main class, omits libraries the JSON lists,
 * and adds log config, heap and window arguments of its own. Most importantly, NeoForge's
 * locally-patched client jar is produced by the installer and appears nowhere here — and adding
 * it to the classpath makes FML believe it is in a dev environment and fail. The runner
 * therefore treats this as the *starting point* for assembly, not the answer.
 */
public record VersionDescriptor(
        String id,
        String mainClass,
        String assetIndexId,
        List<Library> libraries,
        List<Argument> jvmArguments,
        List<Argument> gameArguments) {

    /** A library and the rules deciding whether this platform wants it. */
    public record Library(String path, List<Rule> rules) {}

    /** One argument, possibly gated. A gated argument carries the rules that admit it. */
    public record Argument(String value, List<Rule> rules) {
        public static Argument plain(String value) {
            return new Argument(value, List.of());
        }
    }

    /**
     * @param featureGated arguments gated on a launcher feature -- demo mode, custom resolution,
     *     Quick Play. Laymark opts out of all of them: the harness creates and enters its own
     *     world, and window size is set through the preset rather than the command line.
     */
    public record Rule(boolean allow, String osName, String osArch, boolean featureGated) {

        public boolean admits(HostPlatform platform) {
            if (featureGated) {
                return false;
            }
            if (osName != null && !osName.equals(platform.osName())) {
                return false;
            }
            return osArch == null || osArch.equals(platform.arch());
        }
    }

    public static VersionDescriptor parse(String json) {
        JsonObject root;
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            throw new LaunchException("version descriptor is not a JSON object", e);
        }

        String id = string(root, "id");
        String mainClass = string(root, "mainClass");
        if (mainClass == null) {
            throw new LaunchException("version descriptor has no mainClass");
        }

        String assetIndexId = null;
        if (root.has("assetIndex") && root.get("assetIndex").isJsonObject()) {
            assetIndexId = string(root.getAsJsonObject("assetIndex"), "id");
        }

        List<Library> libraries = new ArrayList<>();
        if (root.has("libraries")) {
            for (JsonElement element : root.getAsJsonArray("libraries")) {
                JsonObject library = element.getAsJsonObject();
                String path = artifactPath(library);
                if (path != null) {
                    libraries.add(new Library(path, rules(library)));
                }
            }
        }

        List<Argument> jvm = List.of();
        List<Argument> game = List.of();
        if (root.has("arguments") && root.get("arguments").isJsonObject()) {
            JsonObject arguments = root.getAsJsonObject("arguments");
            jvm = arguments(arguments, "jvm");
            game = arguments(arguments, "game");
        }

        return new VersionDescriptor(id, mainClass, assetIndexId, libraries, jvm, game);
    }

    private static String artifactPath(JsonObject library) {
        if (!library.has("downloads")) {
            return null;
        }
        JsonObject downloads = library.getAsJsonObject("downloads");
        if (!downloads.has("artifact")) {
            return null;
        }
        return string(downloads.getAsJsonObject("artifact"), "path");
    }

    private static List<Argument> arguments(JsonObject arguments, String key) {
        if (!arguments.has(key)) {
            return List.of();
        }
        List<Argument> parsed = new ArrayList<>();
        for (JsonElement element : arguments.getAsJsonArray(key)) {
            if (element.isJsonPrimitive()) {
                parsed.add(Argument.plain(element.getAsString()));
                continue;
            }
            JsonObject conditional = element.getAsJsonObject();
            List<Rule> rules = rules(conditional);
            JsonElement value = conditional.get("value");
            if (value == null) {
                continue;
            }
            if (value.isJsonPrimitive()) {
                parsed.add(new Argument(value.getAsString(), rules));
            } else if (value.isJsonArray()) {
                for (JsonElement each : value.getAsJsonArray()) {
                    parsed.add(new Argument(each.getAsString(), rules));
                }
            }
        }
        return List.copyOf(parsed);
    }

    private static List<Rule> rules(JsonObject owner) {
        if (!owner.has("rules")) {
            return List.of();
        }
        List<Rule> rules = new ArrayList<>();
        JsonArray array = owner.getAsJsonArray("rules");
        for (JsonElement element : array) {
            JsonObject rule = element.getAsJsonObject();
            boolean allow = !"disallow".equals(string(rule, "action"));
            String osName = null;
            String osArch = null;
            if (rule.has("os") && rule.get("os").isJsonObject()) {
                JsonObject os = rule.getAsJsonObject("os");
                osName = string(os, "name");
                osArch = string(os, "arch");
            }
            rules.add(new Rule(allow, osName, osArch, rule.has("features")));
        }
        return List.copyOf(rules);
    }

    private static String string(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }

    /**
     * Mojang's rule semantics: with no rules, take it. Otherwise start denied and let each
     * matching rule set the verdict, so a later {@code disallow} overrides an earlier
     * {@code allow}.
     */
    public static boolean admitted(List<Rule> rules, HostPlatform platform) {
        if (rules.isEmpty()) {
            return true;
        }
        boolean allowed = false;
        for (Rule rule : rules) {
            if (rule.admits(platform)) {
                allowed = rule.allow();
            }
        }
        return allowed;
    }
}
