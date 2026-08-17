package cx.mia.lucent.laymark.core.harness;

/**
 * The per-frame CPU timings, from widest to narrowest.
 *
 * <p>They nest, and the gaps between them are the useful part: a mod that grows
 * {@link #INTERVAL} without touching {@link #RENDER_CALL} has moved its cost into the client tick
 * or the buffer swap, which points somewhere entirely different than one that grows both.
 */
public enum TimingChannel {

    /**
     * Buffer flip to buffer flip. The whole frame, and the only channel a scenario scores on.
     *
     * <p>This is what a player experiences and what an fps counter reports. It accounts for input
     * polling, the client ticks executed that frame, rendering, the swap, and any wait a framerate
     * cap imposes.
     */
    INTERVAL,

    /**
     * Vanilla's own render-section timer.
     *
     * <p>Starts after the frame's client ticks, sound and input have already run, and is read
     * before the buffer swap — so it is a strict subset of {@link #INTERVAL}, typically by a wide
     * margin. Useful for attributing a regression to rendering; wrong as a headline, and it was
     * the headline until a run caught it reading about 55% of the true interval.
     */
    RENDER_CALL,

    /** The render-submission bracket alone, narrower again than {@link #RENDER_CALL}. */
    SUBMIT
}
