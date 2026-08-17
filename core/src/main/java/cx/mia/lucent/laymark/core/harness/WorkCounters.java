package cx.mia.lucent.laymark.core.harness;

/**
 * How much the game actually got done, read at each end of a capture.
 *
 * <p>Timings alone cannot distinguish a fast stack from a lazy one. A mod that renders fewer
 * sections or streams fewer chunks will post better frame times honestly, and whether that is an
 * optimisation or a downgrade is a judgement a reader can only make with the work counts in front
 * of them. Laymark reports both and does not decide.
 *
 * <p>This is also why a performance mod that deliberately changes the workload — culling is the
 * obvious family — is still a valid comparison rather than a cheat. The counters make the change
 * visible instead of leaving it to be inferred from a suspiciously good number.
 *
 * @param renderedSections chunk sections the renderer drew
 * @param clientChunks chunks the client is holding
 * @param serverChunks chunks the integrated server is holding
 */
public record WorkCounters(int renderedSections, int clientChunks, int serverChunks) {

    /** Difference between two readings. Fields may go negative: unloading is work too. */
    public WorkCounters minus(WorkCounters earlier) {
        return new WorkCounters(
                renderedSections - earlier.renderedSections,
                clientChunks - earlier.clientChunks,
                serverChunks - earlier.serverChunks);
    }
}
