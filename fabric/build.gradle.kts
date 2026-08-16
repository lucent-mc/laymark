plugins {
    id("laymark.java-conventions")
}

description = "Interface-only placeholder for the future Fabric port. Not built against Fabric, not published in 0.x."

// Deliberately has no Fabric dependency and no Loom. 0.x ships NeoForge only; this module
// exists so the loader seam is visible in the source tree and so the eventual port is additive.
//
// Accepted risk, recorded on issue #9: an interface with nothing built against it proves
// nothing. The mitigation is that the trigger contract must be expressible by a Mixin-driven
// implementation, not only by event subscription -- Fabric has no whole-frame event, which is
// the one concrete piece of evidence available about whether the seam actually fits.

dependencies {
    implementation(project(":core"))
}
