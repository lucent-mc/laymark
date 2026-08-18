package cx.mia.lucent.laymark.core.plan;

/**
 * The measured window, one size for the whole run.
 *
 * <p>Run-level rather than per scenario: one process has one window, and resizing it between
 * scenarios would flush driver and swapchain state mid-launch for no benefit anyone asked for.
 * Configurable because the right size is a property of the machine and the question — a 4K panel
 * comparing GPU-bound mods wants a different window than a laptop comparing CPU-bound ones — but
 * it is a <strong>stratum</strong>: two runs at different sizes are never comparable, and the
 * framebuffer readback plus the cross-arm parity gate enforce that within a run.
 *
 * <p>Always windowed, never fullscreen; the position is pinned separately so the runner can dock
 * beside the game.
 */
public record WindowSize(int width, int height) {

    /** A smaller window is less GPU-bound, so CPU-side differences show more clearly. */
    public static final WindowSize DEFAULT = new WindowSize(1600, 900);

    public WindowSize {
        if (width < 320 || height < 240) {
            throw new PlanException(
                    "window must be at least 320x240, got " + width + "x" + height);
        }
        if (width > 16384 || height > 16384) {
            throw new PlanException(
                    "window " + width + "x" + height + " exceeds any real framebuffer");
        }
    }
}
