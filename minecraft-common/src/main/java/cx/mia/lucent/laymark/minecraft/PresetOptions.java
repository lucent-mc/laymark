package cx.mia.lucent.laymark.minecraft;

import com.mojang.blaze3d.platform.FramerateLimitTracker;
import cx.mia.lucent.laymark.core.harness.HarnessException;
import cx.mia.lucent.laymark.core.harness.Preset;
import cx.mia.lucent.laymark.core.harness.PresetReadback;
import cx.mia.lucent.laymark.core.harness.Throttle;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.InactivityFpsLimit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.server.level.ParticleStatus;
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

    /** Clear of the top edge so the title bar stays grabbable. */
    private static final int WINDOW_X = 0;

    private static final int WINDOW_Y = 40;

    /**
     * Applies every setting, unconditionally.
     *
     * <p>Not a diff. {@code OptionInstance#set} skips the option's update callback when the value
     * is unchanged, so a second scenario in the same launch that shares a value with the first
     * would keep the first one's renderer state.
     *
     * <p>Also disables the two vanilla behaviours that silently sabotage an unattended run.
     */
    public static void apply(Preset preset, cx.mia.lucent.laymark.core.plan.WindowSize window) {
        Minecraft minecraft = Minecraft.getInstance();
        Options options = minecraft.options;

        set(options.renderDistance(), preset.renderDistance(), "renderDistance");
        set(options.simulationDistance(), preset.simulationDistance(), "simulationDistance");
        // Mandatory overrides, not preset fields. A cap or vsync clamps frame time to something
        // other than the work being measured.
        set(options.framerateLimit(), Preset.UNLIMITED_FRAMERATE, "framerateLimit");
        set(options.enableVsync(), Preset.VSYNC, "vsync");
        set(options.particles(), particleStatus(preset.particles()), "particles");
        set(options.cloudStatus(), cloudStatus(preset.clouds()), "clouds");
        set(options.entityShadows(), preset.entityShadows(), "entityShadows");
        set(options.biomeBlendRadius(), preset.biomeBlendRadius(), "biomeBlendRadius");
        set(options.fov(), preset.fieldOfView(), "fieldOfView");

        // The free-form namespaces, after the enumerated fields so an explicit
        // minecraft:renderDistance-style entry wins over the shorthand. The "minecraft" namespace
        // reaches any option in the game's own registry; other namespaces need a loader-side
        // handler and fail closed until one exists, because an option someone configured and
        // Laymark ignored is a run measuring the wrong thing.
        for (var namespace : preset.options().entrySet()) {
            if (!"minecraft".equals(namespace.getKey())) {
                throw new cx.mia.lucent.laymark.core.harness.HarnessException(
                        "options namespace '" + namespace.getKey() + "' has no handler yet; only"
                                + " 'minecraft' is wired");
            }
            for (var entry : namespace.getValue().entrySet()) {
                GenericOptions.set(options, entry.getKey(), entry.getValue());
            }
        }

        // Windowed at the run's size. A window that varied with whatever the instance last used
        // would make two machines, or two runs, incomparable for a reason nobody recorded -- so
        // the size is a stratum on the plan, and the framebuffer readback plus the cross-arm
        // parity gate hold it within a run.
        options.fullscreen().set(false);
        Minecraft.getInstance().getWindow().setWindowed(window.width(), window.height());
        // Pinned to the top-left corner, not just to a size. A deterministic position lets the
        // runner's own window dock beside the game instead of underneath it, and where the window
        // sits is as much session state as how big it is.
        org.lwjgl.glfw.GLFW.glfwSetWindowPos(
                Minecraft.getInstance().getWindow().handle(), WINDOW_X, WINDOW_Y);

        // A scripted camera generates no GLFW input, and vanilla reads that as an idle player: at
        // the default AFK setting the framerate is capped to 30 after a minute and 10 after ten.
        // Every capture longer than a minute would otherwise measure the throttle.
        set(options.inactivityFpsLimit(), InactivityFpsLimit.MINIMIZED, "inactivityFpsLimit");

        // Losing focus pauses the integrated server 500ms later. An unattended run on a desktop
        // that does anything else at all would freeze mid-measurement.
        options.pauseOnLostFocus = false;
    }

    /**
     * Reads what the game ended up with.
     *
     * <p>Render distance comes from {@code getEffectiveRenderDistance()} rather than the option,
     * because the server can hand back a smaller radius at login and the renderer honours that
     * one. VSync has no effective accessor at all — the client stores the request, passes it to
     * the graphics device and exposes nothing — so it is echoed back as requested and excluded
     * from the deviation check rather than being compared against itself.
     */
    public static PresetReadback read(Preset requested) {
        Minecraft minecraft = Minecraft.getInstance();
        Options options = minecraft.options;
        var window = minecraft.getWindow();

        // Free-form options read back through each option's own codec, so the comparison is
        // literal-to-literal in one canonical form. A key the registry cannot read is echoed as
        // requested, like vsync -- unverifiable is not the same as deviated.
        java.util.Map<String, java.util.Map<String, String>> effectiveOptions =
                new java.util.LinkedHashMap<>();
        for (var namespace : requested.options().entrySet()) {
            java.util.Map<String, String> values = new java.util.LinkedHashMap<>();
            for (var entry : namespace.getValue().entrySet()) {
                if ("minecraft".equals(namespace.getKey())) {
                    values.put(entry.getKey(), GenericOptions.read(options, entry.getKey()));
                } else {
                    values.put(entry.getKey(), entry.getValue());
                }
            }
            effectiveOptions.put(namespace.getKey(), values);
        }

        Preset effective =
                new Preset(
                        options.getEffectiveRenderDistance(),
                        options.simulationDistance().get(),
                        particleDetail(options.particles().get()),
                        cloudDetail(options.cloudStatus().get()),
                        options.entityShadows().get(),
                        options.biomeBlendRadius().get(),
                        options.fov().get(),
                        effectiveOptions);

        return new PresetReadback(
                effective,
                // getWidth/getHeight are the framebuffer; getScreenWidth/Height are the logical
                // window. They differ on a HiDPI display, and the framebuffer is what gets shaded.
                window.getWidth(),
                window.getHeight(),
                // Window#isFullscreen() returns the requested flag, not the applied one. This is
                // the check vanilla's own Window#setMode uses to decide what is actually going on.
                GLFW.glfwGetWindowMonitor(window.handle()) != 0L);
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

    private static ParticleStatus particleStatus(Preset.ParticleDetail detail) {
        return switch (detail) {
            case ALL -> ParticleStatus.ALL;
            case DECREASED -> ParticleStatus.DECREASED;
            case MINIMAL -> ParticleStatus.MINIMAL;
        };
    }

    private static Preset.ParticleDetail particleDetail(ParticleStatus status) {
        return switch (status) {
            case ALL -> Preset.ParticleDetail.ALL;
            case DECREASED -> Preset.ParticleDetail.DECREASED;
            case MINIMAL -> Preset.ParticleDetail.MINIMAL;
        };
    }

    private static CloudStatus cloudStatus(Preset.CloudDetail detail) {
        return switch (detail) {
            case OFF -> CloudStatus.OFF;
            case FAST -> CloudStatus.FAST;
            case FANCY -> CloudStatus.FANCY;
        };
    }

    private static Preset.CloudDetail cloudDetail(CloudStatus status) {
        return switch (status) {
            case OFF -> Preset.CloudDetail.OFF;
            case FAST -> Preset.CloudDetail.FAST;
            case FANCY -> Preset.CloudDetail.FANCY;
        };
    }
}
