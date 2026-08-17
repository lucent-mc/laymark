package cx.mia.lucent.laymark.runner.launch;

import cx.mia.lucent.laymark.core.Laymark;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the command line for one launch.
 *
 * <p>A pure function: paths in, argument list out, nothing touched on disk. That keeps the part
 * most likely to be wrong in the fast test tier, which matters because a mistake here does not
 * announce itself — it produces a game that boots and measures the wrong thing.
 *
 * <p>Four behaviours are load-bearing and each came out of a probe rather than a document:
 *
 * <ul>
 *   <li><strong>The patched client jar is never added.</strong> NeoForge's installer produces one
 *       locally and the launcher puts it nowhere near the classpath. Adding it makes FML decide
 *       it is in a dev environment and refuse to start.
 *   <li><strong>{@code -Djava.awt.headless=true} is forced.</strong> On a fatal startup error FML
 *       opens a modal AWT dialog and parks on the AWT tree lock: the process never exits and
 *       writes one log line. For an unattended run that is a hang consuming a whole scenario
 *       timeout, not a failure. Headless turns it into a stderr dump and exit code 1.
 *   <li><strong>Auth arguments are present with placeholder values.</strong> Omitting
 *       {@code --accessToken} fails outright; its value is never validated for singleplayer.
 *   <li><strong>Feature-gated arguments are dropped</strong> -- demo, custom resolution, Quick
 *       Play. The harness creates and enters its own world, so a world target must not be passed.
 * </ul>
 */
public final class LaunchAssembly {

    private LaunchAssembly() {}

    /**
     * Fixed min = max, so the heap neither grows mid-capture nor differs between arms. 4 GiB is
     * the modded-pack convention; a pack that genuinely needs more will OOM loudly rather than
     * quietly measure a different heap than its baseline did.
     */
    private static final String HEAP = "4G";

    /**
     * @param port loopback port the runner is already listening on
     * @param token single-use nonce the mod must echo in its first frame
     * @param runId what the harness resolves the config under; with the output directory, the only
     *     run-shaped facts the config itself cannot carry
     */
    public static List<String> assemble(
            VersionDescriptor descriptor,
            InstanceLayout layout,
            HostPlatform platform,
            OfflineIdentity identity,
            int port,
            String token,
            String runId,
            String outputDirectory) {

        if (token == null || token.isBlank()) {
            throw new LaunchException("a launch needs a handshake token");
        }
        if (port <= 0 || port > 65535) {
            throw new LaunchException("port out of range: " + port);
        }
        if (runId == null || runId.isBlank() || outputDirectory == null || outputDirectory.isBlank()) {
            throw new LaunchException("a launch needs a run id and an output directory");
        }

        String classpath = classpath(descriptor, layout, platform);
        Map<String, String> substitutions = substitutions(descriptor, layout, identity, classpath);

        List<String> argv = new ArrayList<>();

        // First, so it cannot be overridden by anything the descriptor supplies.
        argv.add("-Djava.awt.headless=true");
        argv.add("-D" + Laymark.PROPERTY_PORT + "=" + port);
        argv.add("-D" + Laymark.PROPERTY_TOKEN + "=" + token);
        argv.add("-D" + Laymark.PROPERTY_RUN_ID + "=" + runId);
        argv.add("-D" + Laymark.PROPERTY_OUTPUT + "=" + outputDirectory);

        for (VersionDescriptor.Argument argument : descriptor.jvmArguments()) {
            if (VersionDescriptor.admitted(argument.rules(), platform)) {
                argv.add(substitute(argument.value(), substitutions));
            }
        }

        // After the descriptor's arguments, so these win -- the JVM honours the last occurrence.
        // Heap and collector are pinned because they are exactly what two arms must share: a heap
        // that floats with whatever the launcher last used, or a collector that differs between
        // machines, is a variable nobody recorded wearing a mod's name. This pinning is the reason
        // Laymark launches the game itself at all (§5.1).
        argv.add("-Xms" + HEAP);
        argv.add("-Xmx" + HEAP);
        argv.add("-XX:+UseG1GC");

        argv.add(descriptor.mainClass());

        for (VersionDescriptor.Argument argument : descriptor.gameArguments()) {
            if (VersionDescriptor.admitted(argument.rules(), platform)) {
                argv.add(substitute(argument.value(), substitutions));
            }
        }

        // The game opens at the measured size instead of opening at a default and being resized
        // once the harness gets around to it. The preset still enforces the size afterwards; this
        // only spares the visible snap and the seconds spent under a size nobody asked for.
        argv.add("--width");
        argv.add(String.valueOf(Laymark.WINDOW_WIDTH));
        argv.add("--height");
        argv.add(String.valueOf(Laymark.WINDOW_HEIGHT));
        return List.copyOf(argv);
    }

    /**
     * Artifacts the descriptor lists but that must not go on a launch classpath.
     *
     * <p>Verified against a real launcher's command line: it lists these in {@code libraries} and
     * then omits them when launching, and including them breaks the launch with
     * <em>"The patched Minecraft jar is missing"</em>.
     *
     * <p>Both exclusions are principled rather than observed. The NeoForge universal jar is
     * NeoForge's own <em>mod file</em>, which FML discovers through {@code libraryDirectory};
     * putting it on the classpath instead sends discovery down a different path. Installer tools
     * are install-time only and have no business in a running game.
     */
    private static final List<String> NOT_ON_CLASSPATH =
            List.of("/neoforge/", "/installertools/");

    /**
     * Libraries the platform admits, minus the artifacts above, plus the unmodified version jar.
     *
     * <p>Deliberately excludes the locally-patched client jar too; see the class javadoc.
     */
    public static String classpath(
            VersionDescriptor descriptor, InstanceLayout layout, HostPlatform platform) {
        List<String> entries = new ArrayList<>();
        for (VersionDescriptor.Library library : descriptor.libraries()) {
            if (VersionDescriptor.admitted(library.rules(), platform) && onClasspath(library)) {
                entries.add(join(layout.librariesDirectory(), library.path()));
            }
        }
        entries.add(layout.versionJar());
        return String.join(platform.pathSeparator(), entries);
    }

    private static boolean onClasspath(VersionDescriptor.Library library) {
        String path = library.path().replace('\\', '/');
        return NOT_ON_CLASSPATH.stream().noneMatch(path::contains);
    }

    private static Map<String, String> substitutions(
            VersionDescriptor descriptor,
            InstanceLayout layout,
            OfflineIdentity identity,
            String classpath) {

        Map<String, String> values = new LinkedHashMap<>();
        values.put("auth_player_name", identity.username());
        values.put("auth_uuid", identity.uuidArgument());
        values.put("auth_access_token", OfflineIdentity.ACCESS_TOKEN);
        values.put("auth_xuid", OfflineIdentity.XUID);
        values.put("clientid", OfflineIdentity.CLIENT_ID);
        values.put("user_type", "legacy");
        values.put("version_name", descriptor.id() == null ? "unknown" : descriptor.id());
        values.put("version_type", "release");
        values.put("game_directory", layout.gameDirectory());
        values.put("assets_root", layout.assetsDirectory());
        values.put("game_assets", layout.assetsDirectory());
        values.put(
                "assets_index_name",
                descriptor.assetIndexId() == null ? "" : descriptor.assetIndexId());
        values.put("natives_directory", layout.nativesDirectory());
        values.put("library_directory", layout.librariesDirectory());
        values.put("classpath", classpath);
        values.put("launcher_name", Laymark.ID);
        values.put("launcher_version", "0");
        return values;
    }

    /**
     * Replaces {@code ${placeholder}} occurrences. An unknown placeholder is an error rather
     * than a silent empty string: it would otherwise reach the game as literal
     * {@code ${something}} and fail much further away from the cause.
     */
    static String substitute(String template, Map<String, String> values) {
        StringBuilder out = new StringBuilder(template.length());
        int index = 0;
        while (index < template.length()) {
            int open = template.indexOf("${", index);
            if (open < 0) {
                out.append(template, index, template.length());
                break;
            }
            int close = template.indexOf('}', open);
            if (close < 0) {
                out.append(template, index, template.length());
                break;
            }
            out.append(template, index, open);
            String key = template.substring(open + 2, close);
            String value = values.get(key);
            if (value == null) {
                throw new LaunchException(
                        "unknown placeholder ${" + key + "} in launch argument: " + template);
            }
            out.append(value);
            index = close + 1;
        }
        return out.toString();
    }

    private static String join(String directory, String relative) {
        String left = directory.endsWith("/") || directory.endsWith("\\")
                ? directory.substring(0, directory.length() - 1)
                : directory;
        return left + "/" + relative.replace('\\', '/');
    }
}
