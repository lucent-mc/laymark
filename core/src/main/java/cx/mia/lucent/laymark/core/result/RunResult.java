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
 */
public record RunResult(
        String runId, int protocolVersion, List<ScenarioResult> scenarios, List<String> flags) {

    public RunResult {
        if (runId == null || runId.isBlank()) {
            throw new HarnessException("result has no run id");
        }
        if (protocolVersion < 1) {
            throw new HarnessException("result has no protocol version");
        }
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
