package cx.mia.lucent.laymark.runner.launch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Tier 1: no game, no filesystem, no network. Assembly is a pure function by design. */
class LaunchAssemblyTest {

    /** Shaped like a real launcher descriptor, trimmed to the behaviours that matter. */
    private static final String DESCRIPTOR =
            """
            {
              "id": "26.1.2-26.1.2.95",
              "mainClass": "net.neoforged.fml.startup.Client",
              "assetIndex": { "id": "30" },
              "libraries": [
                { "downloads": { "artifact": { "path": "com/example/everywhere/1.0/everywhere-1.0.jar" } } },
                { "downloads": { "artifact": { "path": "org/lwjgl/lwjgl-natives-windows.jar" } },
                  "rules": [ { "action": "allow", "os": { "name": "windows" } } ] },
                { "downloads": { "artifact": { "path": "org/lwjgl/lwjgl-natives-macos.jar" } },
                  "rules": [ { "action": "allow", "os": { "name": "osx" } } ] },
                { "downloads": { "artifact": { "path": "com/example/not-on-mac/1.0/not-on-mac-1.0.jar" } },
                  "rules": [ { "action": "allow" }, { "action": "disallow", "os": { "name": "osx" } } ] }
              ],
              "arguments": {
                "jvm": [
                  "-Djava.library.path=${natives_directory}",
                  "-cp", "${classpath}",
                  { "rules": [ { "action": "allow", "os": { "name": "osx" } } ],
                    "value": "-XstartOnFirstThread" }
                ],
                "game": [
                  "--username", "${auth_player_name}",
                  "--gameDir", "${game_directory}",
                  "--assetIndex", "${assets_index_name}",
                  "--uuid", "${auth_uuid}",
                  "--accessToken", "${auth_access_token}",
                  { "rules": [ { "action": "allow", "features": { "has_quick_plays_support": true } } ],
                    "value": [ "--quickPlayPath", "${quickPlayPath}" ] },
                  { "rules": [ { "action": "allow", "features": { "is_demo_user": true } } ],
                    "value": "--demo" }
                ]
              }
            }
            """;

    private static final InstanceLayout LAYOUT =
            new InstanceLayout("/inst", "/meta/libraries", "/meta/natives/26.1.2", "/meta/assets",
                    "/meta/versions/26.1.2/26.1.2.jar");

    private static List<String> assemble(HostPlatform platform) {
        return LaunchAssembly.assemble(
                VersionDescriptor.parse(DESCRIPTOR),
                LAYOUT,
                platform,
                OfflineIdentity.of("miacx"),
                55098,
                "nonce-xyz");
    }

    @Test
    void forcesHeadlessBeforeAnythingTheDescriptorSupplies() {
        List<String> argv = assemble(HostPlatform.windowsX64());
        assertEquals("-Djava.awt.headless=true", argv.get(0),
                "FML answers a fatal startup error with a modal AWT dialog and then never exits;"
                        + " headless must not be overridable by the descriptor");
    }

    @Test
    void passesTheHandshakeOnTheCommandLine() {
        List<String> argv = assemble(HostPlatform.windowsX64());
        assertTrue(argv.contains("-Dlaymark.port=55098"), argv.toString());
        assertTrue(argv.contains("-Dlaymark.token=nonce-xyz"), argv.toString());
    }

    @Test
    void rejectsAMissingHandshake() {
        var descriptor = VersionDescriptor.parse(DESCRIPTOR);
        var identity = OfflineIdentity.of("miacx");
        assertThrows(LaunchException.class,
                () -> LaunchAssembly.assemble(descriptor, LAYOUT, HostPlatform.windowsX64(), identity, 55098, ""));
        assertThrows(LaunchException.class,
                () -> LaunchAssembly.assemble(descriptor, LAYOUT, HostPlatform.windowsX64(), identity, 0, "t"));
    }

    /** Auth flags must be present. Their values are never validated for singleplayer. */
    @Test
    void suppliesPlaceholderCredentials() {
        List<String> argv = assemble(HostPlatform.windowsX64());
        assertEquals("0", argv.get(argv.indexOf("--accessToken") + 1));
        assertEquals("miacx", argv.get(argv.indexOf("--username") + 1));
    }

    /**
     * The value a real probe run produced for this username. Player data in a save is keyed by
     * UUID, so drift here would give each arm a different player state.
     */
    @Test
    void offlineUuidIsTheKnownDeterministicValue() {
        assertEquals("47b64f8b40b13e4fa68bd7539560257c", OfflineIdentity.of("miacx").uuidArgument());
        assertEquals(OfflineIdentity.of("miacx").uuid(), OfflineIdentity.of("miacx").uuid());
    }

    @Test
    void dropsFeatureGatedArguments() {
        List<String> argv = assemble(HostPlatform.windowsX64());
        assertFalse(argv.contains("--demo"), "demo mode is never wanted");
        assertFalse(argv.contains("--quickPlayPath"),
                "the harness creates and enters its own world, so no world target is passed");
    }

    @Test
    void appliesOsRulesToLibraries() {
        String windows = LaunchAssembly.classpath(
                VersionDescriptor.parse(DESCRIPTOR), LAYOUT, HostPlatform.windowsX64());
        String macos = LaunchAssembly.classpath(
                VersionDescriptor.parse(DESCRIPTOR), LAYOUT, HostPlatform.macosArm64());

        assertTrue(windows.contains("lwjgl-natives-windows.jar"));
        assertFalse(windows.contains("lwjgl-natives-macos.jar"));
        assertTrue(macos.contains("lwjgl-natives-macos.jar"));
        assertFalse(macos.contains("lwjgl-natives-windows.jar"));
        assertTrue(windows.contains("everywhere-1.0.jar"), "unruled libraries go everywhere");
    }

    /** Mojang's semantics: a later matching disallow overrides an earlier allow. */
    @Test
    void laterDisallowOverridesEarlierAllow() {
        String linux = LaunchAssembly.classpath(
                VersionDescriptor.parse(DESCRIPTOR), LAYOUT, HostPlatform.linuxX64());
        String macos = LaunchAssembly.classpath(
                VersionDescriptor.parse(DESCRIPTOR), LAYOUT, HostPlatform.macosArm64());
        assertTrue(linux.contains("not-on-mac-1.0.jar"));
        assertFalse(macos.contains("not-on-mac-1.0.jar"));
    }

    @Test
    void appliesOsRulesToJvmArguments() {
        assertFalse(assemble(HostPlatform.windowsX64()).contains("-XstartOnFirstThread"));
        assertTrue(assemble(HostPlatform.macosArm64()).contains("-XstartOnFirstThread"));
    }

    @Test
    void usesThePlatformPathSeparator() {
        var descriptor = VersionDescriptor.parse(DESCRIPTOR);
        assertTrue(LaunchAssembly.classpath(descriptor, LAYOUT, HostPlatform.windowsX64()).contains(";"));
        assertTrue(LaunchAssembly.classpath(descriptor, LAYOUT, HostPlatform.linuxX64()).contains(":"));
    }

    /**
     * The locally-patched client jar is produced by NeoForge's installer, appears nowhere in the
     * descriptor, and must stay off the classpath: adding it makes FML decide it is in a dev
     * environment and refuse to start.
     */
    @Test
    void classpathIsExactlyAdmittedLibrariesPlusTheUnmodifiedVersionJar() {
        String cp = LaunchAssembly.classpath(
                VersionDescriptor.parse(DESCRIPTOR), LAYOUT, HostPlatform.windowsX64());
        List<String> entries = List.of(cp.split(";"));
        assertEquals(4, entries.size(), cp);
        assertEquals("/meta/versions/26.1.2/26.1.2.jar", entries.get(entries.size() - 1));
        assertFalse(cp.contains("patched"), cp);
    }

    /**
     * The descriptor lists artifacts a launch must not put on the classpath. Verified the hard
     * way: including them fails the launch with "The patched Minecraft jar is missing", because
     * the NeoForge universal jar is its own mod file -- discovered through libraryDirectory --
     * and putting it on the classpath sends FML's discovery down a different path.
     */
    @Test
    void excludesModFilesAndInstallerToolsFromTheClasspath() {
        String descriptor =
                """
                { "id": "v", "mainClass": "M", "assetIndex": { "id": "1" }, "libraries": [
                  { "downloads": { "artifact": { "path": "com/example/keep/1.0/keep-1.0.jar" } } },
                  { "downloads": { "artifact": { "path": "net/neoforged/neoforge/26.1.2.95/neoforge-26.1.2.95-universal.jar" } } },
                  { "downloads": { "artifact": { "path": "net/neoforged/installertools/4.0.12/installertools-4.0.12-fatjar.jar" } } }
                ] }
                """;
        String cp = LaunchAssembly.classpath(
                VersionDescriptor.parse(descriptor), LAYOUT, HostPlatform.windowsX64());
        assertTrue(cp.contains("keep-1.0.jar"), cp);
        assertFalse(cp.contains("universal"), cp);
        assertFalse(cp.contains("installertools"), cp);
    }

    @Test
    void mainClassSeparatesJvmArgumentsFromGameArguments() {
        List<String> argv = assemble(HostPlatform.windowsX64());
        int mainClass = argv.indexOf("net.neoforged.fml.startup.Client");
        assertTrue(mainClass > 0);
        assertTrue(argv.indexOf("-cp") < mainClass, "-cp is a JVM argument");
        assertTrue(argv.indexOf("--username") > mainClass, "--username is a game argument");
    }

    @Test
    void rejectsUnknownPlaceholders() {
        LaunchException e = assertThrows(LaunchException.class,
                () -> LaunchAssembly.substitute("--thing=${no_such_key}", java.util.Map.of()));
        assertTrue(e.getMessage().contains("no_such_key"), e.getMessage());
    }

    @Test
    void substitutesRepeatedAndAdjacentPlaceholders() {
        var values = java.util.Map.of("a", "1", "b", "2");
        assertEquals("1-2-1", LaunchAssembly.substitute("${a}-${b}-${a}", values));
        assertEquals("12", LaunchAssembly.substitute("${a}${b}", values));
        assertEquals("plain", LaunchAssembly.substitute("plain", values));
    }

    @Test
    void rejectsMalformedDescriptors() {
        assertThrows(LaunchException.class, () -> VersionDescriptor.parse("not json"));
        assertThrows(LaunchException.class, () -> VersionDescriptor.parse("{}"));
    }
}
