package cx.mia.lucent.laymark.core.stats;

import cx.mia.lucent.laymark.core.harness.HarnessException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What this machine's run-to-run wobble has looked like, remembered between runs.
 *
 * <p>Noise is run-to-run variation with nothing changed. It is machine-specific and
 * metric-specific, so it can never ship as a constant, and it is estimated from the baseline runs
 * a schedule already contains rather than from a calibration phase — the baseline is the slowest
 * arm, and paying for it twice buys nothing.
 *
 * <p><strong>Stored variance may only widen an interval, never narrow it.</strong> That single rule
 * is what makes the profile safe to keep: a stale entry after a driver update or a hardware change
 * cannot produce a confident wrong answer, only a cautious one. The worst case is a small real gain
 * going unreported, which is the failure a benchmark should prefer.
 *
 * <p>Local only. Nothing here leaves the machine.
 *
 * @param fingerprint identifies the hardware and driver state the observations came from; a change
 *     is detected by the key simply not matching, which needs no separate machine-change logic
 * @param relativeSpread per scenario, the observed standard deviation as a fraction of the mean
 */
public record MachineProfile(String fingerprint, Map<String, Double> relativeSpread) {

    public MachineProfile {
        if (fingerprint == null || fingerprint.isBlank()) {
            throw new HarnessException("a machine profile has no fingerprint");
        }
        relativeSpread = Map.copyOf(relativeSpread);
    }

    public static MachineProfile empty(String fingerprint) {
        return new MachineProfile(fingerprint, Map.of());
    }

    /**
     * Widens an interval to at least what this machine has historically wobbled by.
     *
     * <p>One-directional by construction: {@code max} of the measured margin and the remembered
     * one. An entry that has gone stale can only make Laymark more cautious.
     *
     * @param scenarioId the profile is per scenario, because a chunk-loading scenario and a
     *     resident-render one wobble by different amounts on the same machine
     */
    public double widen(String scenarioId, double marginPercent) {
        Double remembered = relativeSpread.get(scenarioId);
        return remembered == null ? marginPercent : Math.max(marginPercent, remembered);
    }

    /**
     * Folds a fresh observation in.
     *
     * <p>Keeps the larger of the two rather than averaging. Averaging would let a quiet run erode
     * the record of a noisy one, and the record exists to stop Laymark being confident on a machine
     * that has been noisy before.
     */
    public MachineProfile observe(String scenarioId, double spreadPercent) {
        if (spreadPercent < 0) {
            throw new HarnessException("spread cannot be negative");
        }
        Map<String, Double> updated = new LinkedHashMap<>(relativeSpread);
        updated.merge(scenarioId, spreadPercent, Math::max);
        return new MachineProfile(fingerprint, updated);
    }

    /** Whether this profile describes the machine now in front of us. */
    public boolean matches(String currentFingerprint) {
        return fingerprint.equals(currentFingerprint);
    }
}
