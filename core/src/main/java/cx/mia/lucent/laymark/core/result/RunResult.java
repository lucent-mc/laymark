package cx.mia.lucent.laymark.core.result;

import cx.mia.lucent.laymark.core.harness.HarnessException;
import java.util.List;

/**
 * Everything one launch produced, written next to the plan that caused it.
 *
 * <p>Self-contained on purpose. A result read a year later must be interpretable without the
 * config that generated it, the instance it ran on, or the version of Laymark that wrote it.
 *
 * @param protocolVersion what wrote this, so a future reader knows which shape to expect
 * @param scenarioListRevision fingerprint of the resolved scenario list, order included. Array
 *     position is part of a scenario's identity, so results from different revisions must never
 *     be pooled — and this is what makes that refusable later.
 */
 /* @param loadedMods the loader's own account of what loaded, by mod id — the only in-process
  *     evidence that materialisation produced the arm that was asked for */
public record RunResult(
        String runId,
        int protocolVersion,
        String scenarioListRevision,
        List<String> loadedMods,
        List<ScenarioResult> scenarios,
        List<String> flags) {

    public RunResult {
        if (runId == null || runId.isBlank()) {
            throw new HarnessException("result has no run id");
        }
        if (protocolVersion < 1) {
            throw new HarnessException("result has no protocol version");
        }
        // Absent on documents from before the field existed; "unrecorded" keeps them readable
        // while still refusing to look like any real revision.
        scenarioListRevision =
                scenarioListRevision == null || scenarioListRevision.isBlank()
                        ? "unrecorded"
                        : scenarioListRevision;
        loadedMods = loadedMods == null ? List.of() : List.copyOf(loadedMods);
        scenarios = scenarios == null ? List.of() : List.copyOf(scenarios);
        flags = flags == null ? List.of() : List.copyOf(flags);
    }

    /** True when every scenario produced a usable measurement. */
    public boolean complete() {
        return !scenarios.isEmpty() && scenarios.stream().allMatch(ScenarioResult::measured);
    }

    public List<ScenarioResult> failures() {
        return scenarios.stream()
                .filter(scenario -> scenario.outcome() == ScenarioResult.Outcome.FAILED)
                .toList();
    }
}
