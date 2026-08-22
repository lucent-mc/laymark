package cx.mia.lucent.laymark.minecraft;

import com.mojang.blaze3d.platform.FramerateLimitTracker;
import cx.mia.lucent.laymark.core.harness.HarnessException;
import cx.mia.lucent.laymark.core.harness.Preset;
import cx.mia.lucent.laymark.core.harness.PresetReadback;
import cx.mia.lucent.laymark.core.harness.Throttle;
import net.minecraft.client.InactivityFpsLimit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import org.lwjgl.glfw.GLFW;

/**
 * Translates a {@link Preset} to and from vanilla's {@code Options}.
 *
 * <p>Both directions matter, and they are not symmetric. Writing a setting is a request; reading
 * it back is the only way to learn whether the request was honoured. {@code OptionInstance#set}
 * replaces an out-of-range value with the option's <em>default</em> — not a clamp, not an error,
 * just a log line — so a preset asking for render distance 32 on a small heap silently produces
 * 12, and both arms would report the number they asked for if nobody looked.
 *
 * <p>Must be called on the client thread. Several setters reach code that asserts it.
 */
public final class PresetOptions {

    private PresetOptions() {}

    /**
     * The mandatory overrides, refused even through the settings map — by their {@code
     * options.txt} keys, which is the vocabulary the map speaks. Each one either clamps frame
     * time to something other than the work being measured, changes the window out from under the
     * run, or re-labels settings Laymark pins individually.
     */
    private static final java.util.Set<String> FORCED_KEYS =
            java.util.Set.of(
                    "maxFps",
                    "enableVsync",
                    "fullscreen",
                    "exclusiveFullscreen",
                    "inactivityFpsLimit",
                    "graphicsPreset");

    /** Clear of the top edge so the title bar stays grabbable. */
    private static final int WINDOW_X = 0;

    private static final int WINDOW_Y = 40;

    /**
     * Applies every configured setting, unconditionally, then the mandatory overrides.
     *
     * <p>Not a diff. {@code OptionInstance#set} skips the option's update callback when the value
     * is unchanged, so a second scenario in the same launch that shares a value with the first
     * would keep the first one's renderer state.
     *
     * <p>The configured pass runs through {@link GenericOptions} — the game's own options
     * registry — with {@link HumaneOptions} translating the few spellings whose codec form is
     * unreadable. The overrides run <em>after</em> it, so nothing configured can win against
     * them, and the graphics preset is forced to CUSTOM last of all.
     */
    public static void apply(Preset preset, cx.mia.lucent.laymark.core.plan.WindowSize window) {
        Minecraft minecraft = Minecraft.getInstance();
        Options options = minecraft.options;

        for (var namespace : preset.values().entrySet()) {
            // Other namespaces are for mods that expose settings through a loader config API.
            // They fail closed until a loader-side handler is wired, because an option someone
            // configured and Laymark ignored is a run measuring the wrong thing.
            if (!"minecraft".equals(namespace.getKey())) {
                throw new HarnessException(
                        "options namespace '" + namespace.getKey() + "' has no handler yet; only"
                                + " 'minecraft' is wired");
            }
            for (var entry : namespace.getValue().entrySet()) {
                HumaneOptions.GameForm form =
                        HumaneOptions.toGame(entry.getKey(), entry.getValue());
                if (FORCED_KEYS.contains(form.key())) {
                    throw new HarnessException(
                            "'" + entry.getKey() + "' is a mandatory override and cannot be"
                                    + " configured; a config that could set it could censor its"
                                    + " own results");
                }
                GenericOptions.set(options, form.key(), form.literal());
            }
        }

        // Mandatory overrides, not settings. A cap or vsync clamps frame time to something other
        // than the work being measured.
        set(options.framerateLimit(), Preset.UNLIMITED_FRAMERATE, "framerateLimit");
        set(options.enableVsync(), Preset.VSYNC, "vsync");

        // A scripted camera generates no GLFW input, and vanilla reads that as an idle player: at
        // the default AFK setting the framerate is capped to 30 after a minute and 10 after ten.
        // Every capture longer than a minute would otherwise measure the throttle.
        set(options.inactivityFpsLimit(), InactivityFpsLimit.MINIMIZED, "inactivityFpsLimit");

        // Windowed at the run's size. A window that varied with whatever the instance last used
        // would make two machines, or two runs, incomparable for a reason nobody recorded -- so
        // the size is a stratum on the plan, and the framebuffer readback plus the cross-arm
        // parity gate hold it within a run.
        options.fullscreen().set(false);
        minecraft.getWindow().setWindowed(window.width(), window.height());
        // Pinned to the top-left corner, not just to a size. A deterministic position lets the
        // runner's own window dock beside the game instead of underneath it, and where the window
        // sits is as much session state as how big it is.
        GLFW.glfwSetWindowPos(minecraft.getWindow().handle(), WINDOW_X, WINDOW_Y);

        // The graphics preset is forced to CUSTOM, last, after every setting it summarises.
        // Fast/Fancy/Fabulous is a label over member settings that Laymark pins individually --
        // and vanilla flips the label to CUSTOM whenever any member changes anyway, so CUSTOM is
        // the only honest value and any drift away from it means a mod moved a member setting.
        set(options.graphicsPreset(), net.minecraft.client.GraphicsPreset.CUSTOM, "graphicsPreset");

        // Losing focus pauses the integrated server 500ms later. An unattended run on a desktop
        // that does anything else at all would freeze mid-measurement.
        options.pauseOnLostFocus = false;
    }

    /**
     * Reads what the game ended up with, per requested key.
     *
     * <p>Each entry reads back through the option's own codec. When the raw form matches what was
     * requested, the requested spelling is echoed — honoured is honoured, whatever the spelling —
     * and a mismatch is reported in the humane form, so a deviation names {@code fieldOfView: 90},
     * not {@code fov: 0.5}.
     *
     * <p>Render distance is the one special case: it reads from {@code
     * getEffectiveRenderDistance()} rather than the option, because the server can hand back a
     * smaller radius at login and the renderer honours that one. It is also read whether or not
     * the preset pinned it, since the cross-arm parity gate wants the value that decided how much
     * work the arm did.
     */
    public static PresetReadback read(Preset requested) {
        Minecraft minecraft = Minecraft.getInstance();
        Options options = minecraft.options;
        var window = minecraft.getWindow();

        java.util.Map<String, java.util.Map<String, String>> effectiveValues =
                new java.util.LinkedHashMap<>();
        for (var namespace : requested.values().entrySet()) {
            java.util.Map<String, String> values = new java.util.LinkedHashMap<>();
            for (var entry : namespace.getValue().entrySet()) {
                values.put(
                        entry.getKey(),
                        "minecraft".equals(namespace.getKey())
                                ? effectiveMinecraft(options, entry.getKey(), entry.getValue())
                                : entry.getValue());
            }
            effectiveValues.put(namespace.getKey(), values);
        }
        // Always read, even unrequested: this is the value the parity gate compares across arms,
        // and the setting a culling or distance mod is most likely to move.
        effectiveValues
                .computeIfAbsent("minecraft", ignored -> new java.util.LinkedHashMap<>())
                .putIfAbsent(
                        "renderDistance", String.valueOf(options.getEffectiveRenderDistance()));

        return new PresetReadback(
                new Preset(effectiveValues),
                // getWidth/getHeight are the framebuffer; getScreenWidth/Height are the logical
                // window. They differ on a HiDPI display, and the framebuffer is what gets shaded.
                window.getWidth(),
                window.getHeight(),
                // Window#isFullscreen() returns the requested flag, not the applied one. This is
                // the check vanilla's own Window#setMode uses to decide what is actually going on.
                GLFW.glfwGetWindowMonitor(window.handle()) != 0L);
    }

    /**
     * One effective value, in the spelling the request used.
     *
     * <p>The comparison happens in codec space — the request's literal translated forward, the
     * game's value read back raw — so two spellings of one value never read as a deviation.
     */
    private static String effectiveMinecraft(Options options, String key, String requestedLiteral) {
        if ("renderDistance".equals(key)) {
            String effective = String.valueOf(options.getEffectiveRenderDistance());
            return effective.equals(requestedLiteral.trim()) ? requestedLiteral : effective;
        }
        HumaneOptions.GameForm requested = HumaneOptions.toGame(key, requestedLiteral);
        String raw = GenericOptions.read(options, requested.key());
        return raw.equals(requested.literal()) ? requestedLiteral : HumaneOptions.fromGame(key, raw);
    }

    /**
     * Why the framerate is being held below the configured cap, or null when it is not.
     *
     * <p>Checked around a capture rather than before it. Throttling starts partway through a long
     * window, so a clean reading at the start proves nothing about the samples that follow.
     */
    public static String activeThrottle() {
        FramerateLimitTracker tracker = Minecraft.getInstance().getFramerateLimitTracker();
        FramerateLimitTracker.FramerateThrottleReason reason = tracker.getThrottleReason();
        return reason == FramerateLimitTracker.FramerateThrottleReason.NONE
                ? null
                : reason + " (capped at " + tracker.getFramerateLimit() + " fps)";
    }

    /**
     * Translates vanilla's throttle reason into the loader-free enum results are written in.
     *
     * <p>An explicit switch rather than a name lookup, so a renamed or added constant is a compile
     * error here instead of an unrecognised value in a result file a year from now.
     */
    public static Throttle throttleOf(FramerateLimitTracker.FramerateThrottleReason reason) {
        return reason == null
                ? Throttle.NONE
                : switch (reason) {
                    case NONE -> Throttle.NONE;
                    case WINDOW_ICONIFIED -> Throttle.WINDOW_ICONIFIED;
                    case LONG_AFK -> Throttle.LONG_AFK;
                    case SHORT_AFK -> Throttle.SHORT_AFK;
                    case OUT_OF_LEVEL_MENU -> Throttle.OUT_OF_LEVEL_MENU;
                };
    }

    /**
     * Sets and immediately confirms.
     *
     * <p>Confirming here rather than only at readback locates the failure: at readback an
     * unexpected value could equally be a mod that overwrote it during world load, and those two
     * causes want different fixes.
     */
    private static <T> void set(OptionInstance<T> option, T value, String name) {
        option.set(value);
        T stored = option.get();
        if (!value.equals(stored)) {
            throw new HarnessException(
                    "the game refused " + name + " " + value + " and substituted " + stored
                            + "; vanilla replaces an unacceptable value with the option default");
        }
    }
}
