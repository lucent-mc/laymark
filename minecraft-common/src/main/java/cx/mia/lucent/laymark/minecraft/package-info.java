/**
 * Vanilla Minecraft implementation shared by every loader.
 *
 * <p>May import Minecraft and LWJGL. <strong>May not import a loader.</strong> NeoForge is
 * unavoidably on this module's compile classpath, because ModDevGradle supplies Minecraft
 * through it, so the boundary is enforced by {@code purityCheck} scanning what the compiled
 * bytecode actually references rather than by what is available to it.
 *
 * <p>The split that defines this module: the measured <em>quantity</em> comes from vanilla and
 * lives here; the <em>trigger</em> is loader-specific and lives in a loader module. NeoForge
 * supplies {@code FlipFrameEvent}; Fabric has no whole-frame event and will need a Mixin, so
 * the trigger contract must be expressible by a Mixin-driven implementation and not only by
 * event subscription.
 *
 * <p>This module will hold world creation, the options surface and its readback, readiness
 * barriers, frame and GPU sampling, and schematic placement.
 */
package cx.mia.lucent.laymark.minecraft;
