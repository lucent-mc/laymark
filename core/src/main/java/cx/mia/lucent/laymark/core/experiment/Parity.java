package cx.mia.lucent.laymark.core.experiment;

import cx.mia.lucent.laymark.core.harness.PresetReadback;
import java.util.ArrayList;
import java.util.List;

/**
 * The cross-arm gate: two arms are only comparable if the game gave them the same stimulus.
 *
 * <p>World, seed, pose and capture duration are identical across arms <em>by construction</em> —
 * every arm resolves the same plan. What construction cannot guarantee is what the game did with
 * it: the <strong>effective</strong> settings and the framebuffer come from readback, and a mod in
 * one arm that rewrites render distance, or a driver that hands one arm a different framebuffer,
 * produces two internally consistent runs measuring different work. Per-arm verification already
 * catches drift from the <em>request</em>; this catches the arm that deviated from the <em>other
 * arms</em> in a way no single-arm check can see — including the requested-but-unverifiable
 * settings, where both arms may be off but must at least be off identically.
 *
 * <p>A mismatch is a hard failure, not a flag. The spec calls stimulus parity a gate (§9): a
 * comparison across different stimuli is not a worse comparison, it is not a comparison.
 */
public final class Parity {

    private Parity() {}

    /**
     * Everything about one scenario's stimulus that must match across arms, as one string per
     * difference; empty means the arms match.
     *
     * @param reference the first arm that ran this scenario, which defines what "the" stimulus is
     */
    public static List<String> compare(
            String scenarioId,
            String referenceArm,
            PresetReadback reference,
            String observedArm,
            PresetReadback observed) {

        List<String> mismatches = new ArrayList<>();
        for (String deviation : observed.effective().equals(reference.effective())
                ? List.<String>of()
                : reference.effective().describeDifferences(observed.effective())) {
            mismatches.add(prefix(scenarioId, referenceArm, observedArm) + deviation);
        }
        if (reference.framebufferWidth() != observed.framebufferWidth()
                || reference.framebufferHeight() != observed.framebufferHeight()) {
            mismatches.add(
                    prefix(scenarioId, referenceArm, observedArm)
                            + "framebuffer: "
                            + reference.framebufferWidth() + "x" + reference.framebufferHeight()
                            + " vs "
                            + observed.framebufferWidth() + "x" + observed.framebufferHeight());
        }
        if (reference.fullscreen() != observed.fullscreen()) {
            mismatches.add(
                    prefix(scenarioId, referenceArm, observedArm)
                            + "fullscreen: "
                            + reference.fullscreen() + " vs " + observed.fullscreen());
        }
        return List.copyOf(mismatches);
    }

    private static String prefix(String scenarioId, String referenceArm, String observedArm) {
        return scenarioId + ": " + observedArm + " differs from " + referenceArm + " on ";
    }
}
