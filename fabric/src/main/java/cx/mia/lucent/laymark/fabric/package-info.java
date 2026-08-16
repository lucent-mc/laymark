/**
 * Placeholder for the future Fabric port. Not built against Fabric, not published in 0.x.
 *
 * <p>This module exists so the loader seam is visible in the source tree and so the eventual
 * port is additive rather than an extraction. It deliberately has no Fabric dependency and no
 * Loom, because 0.x ships NeoForge only.
 *
 * <p><strong>Accepted risk:</strong> an interface with nothing built against it proves nothing.
 * A seam only demonstrates that it fits when something is built against it.
 *
 * <p><strong>Mitigation:</strong> one concrete constraint is already known and must shape the
 * contract now. NeoForge supplies {@code FlipFrameEvent}, so its trigger is an event
 * subscription. Fabric has <em>no whole-frame event</em>, so its trigger will be a Mixin. The
 * trigger contract must therefore be expressible by a Mixin-driven implementation, not only by
 * event subscription -- that asymmetry is the single piece of evidence available about whether
 * this seam actually fits, and the build must tolerate a Mixin here while NeoForge has none.
 */
package cx.mia.lucent.laymark.fabric;
