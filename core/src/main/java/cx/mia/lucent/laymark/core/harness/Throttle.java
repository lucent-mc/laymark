package cx.mia.lucent.laymark.core.harness;

/**
 * Why the client was holding the framerate below its configured cap, recorded per frame.
 *
 * <p>Per frame rather than per capture because throttling engages partway through a window. A
 * check at the start proves nothing about the samples that follow, and the throttles that matter
 * here are the time-based ones — vanilla drops an input-free session to 30 fps after a minute and
 * 10 after ten, which is longer than a short capture and shorter than a long one.
 *
 * <p>Reading a field on the hot path and judging it afterwards keeps the cost of measuring out of
 * the thing being measured.
 */
public enum Throttle {

    /** Not throttled. The only value a scored capture may contain. */
    NONE,

    /** The window was minimised; vanilla renders at 10 fps. */
    WINDOW_ICONIFIED,

    /** No input for ten minutes: 10 fps. A scripted camera generates no input at all. */
    LONG_AFK,

    /** No input for one minute: capped at 30 fps. */
    SHORT_AFK,

    /** Sitting in a menu with no level loaded: 60 fps. */
    OUT_OF_LEVEL_MENU
}
