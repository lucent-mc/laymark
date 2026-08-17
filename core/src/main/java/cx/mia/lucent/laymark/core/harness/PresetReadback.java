package cx.mia.lucent.laymark.core.harness;

import java.util.ArrayList;
import java.util.List;

/**
 * What the game actually has after a preset was applied, plus the environmental facts a preset
 * cannot set.
 *
 * <p>The gap between requested and effective is the point. Vanilla replaces an unacceptable value
 * with the option's default without complaint, a driver can decline a vsync request, and a mod is
 * free to overwrite a setting after it was applied. Any of these produces a run that is
 * internally consistent, finishes cleanly, and answers a different question than the one asked.
 *
 * @param effective the same shape as the request, holding what the game reports
 * @param framebufferWidth observed, not requested — resolution dominates GPU time, so it is
 *     recorded as a stratum and compared across arms rather than forced
 */
public record PresetReadback(
        Preset effective, int framebufferWidth, int framebufferHeight, boolean fullscreen) {

    public PresetReadback {
        if (effective == null) {
            throw new HarnessException("readback has no effective preset");
        }
        if (framebufferWidth <= 0 || framebufferHeight <= 0) {
            throw new HarnessException(
                    "framebuffer must have positive extent, got "
                            + framebufferWidth
                            + "x"
                            + framebufferHeight);
        }
    }

    /**
     * Human-readable descriptions of every setting the game did not honour.
     *
     * <p>Returns findings rather than throwing: the caller decides whether a deviation fails the
     * scenario or is recorded against it, and that decision differs by phase.
     *
         */
    public List<String> deviationsFrom(Preset requested) {
        List<String> deviations = new ArrayList<>();
        compare(deviations, "renderDistance", requested.renderDistance(), effective.renderDistance());
        compare(deviations, "simulationDistance", requested.simulationDistance(),
                effective.simulationDistance());
        compare(deviations, "particles", requested.particles(), effective.particles());
        compare(deviations, "clouds", requested.clouds(), effective.clouds());
        compare(deviations, "entityShadows", requested.entityShadows(), effective.entityShadows());
        compare(deviations, "biomeBlendRadius", requested.biomeBlendRadius(),
                effective.biomeBlendRadius());
        compare(deviations, "fieldOfView", requested.fieldOfView(), effective.fieldOfView());
        return List.copyOf(deviations);
    }

    private static void compare(List<String> into, String field, Object requested, Object effective) {
        if (!requested.equals(effective)) {
            into.add(field + ": requested " + requested + ", got " + effective);
        }
    }

    public boolean honoured(Preset requested) {
        return deviationsFrom(requested).isEmpty();
    }
}
