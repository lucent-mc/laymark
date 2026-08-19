package cx.mia.lucent.laymark.core.protocol;

import cx.mia.lucent.laymark.core.Phase;

/**
 * One message on the runner/harness channel.
 *
 * <p>The channel is a loopback socket carrying newline-delimited JSON, chosen so the wire format
 * and the durable format are <strong>byte-identical</strong>: the runner appends each received
 * line straight into {@code events.jsonl} without reparsing or translating.
 *
 * <p>Traffic is <strong>one-way after the handshake</strong>. Plans are fully resolved before
 * launch, so there is nothing for the runner to say mid-flight, and the correct abort for a
 * benchmark is killing the process and rolling back the transaction rather than negotiating a
 * shutdown.
 *
 * <p>Frames are deliberately flat — primitives, strings and enums only. Nesting a polymorphic
 * type inside a frame would need custom serialization on both sides for no benefit.
 */
public sealed interface Frame {

    /**
     * First frame the mod sends. The runner validates all three fields before the run proceeds.
     *
     * @param token the nonce the runner passed on the command line. Loopback is reachable by any
     *     local process, so this is the access control.
     * @param protocolVersion exact-matched. A 0.3.1 runner talks happily to a 0.3.2 mod when the
     *     protocol did not change; a real change fails legibly rather than misparsing.
     * @param pid the game's own {@code ProcessHandle.current().pid()}
     */
    record Hello(String token, int protocolVersion, long pid) implements Frame {}

    /** Liveness. Absence over a timeout means hung; socket close means dead. */
    record Heartbeat(long monotonicNanos) implements Frame {}

    record ScenarioStarted(String scenarioId, int repetition) implements Frame {}

    record PhaseEntered(String scenarioId, Phase phase, long monotonicNanos) implements Frame {}

    /**
     * @param ok false means the scenario was invalidated — a precondition unmet, a setting
     *     drifted mid-capture, a throttle fired. Not a performance result.
     */
    record ScenarioFinished(String scenarioId, boolean ok, String detail) implements Frame {}

    /**
     * One scenario's numbers, sent the moment the capture closes — hours before the arm's result
     * file exists. Streaming courtesy only: the result file stays the authoritative record, and a
     * run that never sends this frame is merely quieter, not less correct.
     *
     * @param pass the {@code Pass} name; cold passes are context, warm passes are the score
     * @param scoredMillis the scenario's scored metric under the plan's stop condition
     */
    record ScenarioMeasured(
            String scenarioId,
            int repetition,
            String pass,
            double scoredMillis,
            Double mspt,
            Double fps,
            Double msPerChunk)
            implements Frame {}

    /** Terminal, unsuccessful. */
    record RunFailed(String reason, String detail) implements Frame {}

    /** Terminal, successful. */
    record RunFinished(String resultPath) implements Frame {}
}
