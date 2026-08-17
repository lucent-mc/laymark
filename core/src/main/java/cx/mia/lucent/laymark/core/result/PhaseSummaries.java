package cx.mia.lucent.laymark.core.result;

import cx.mia.lucent.laymark.core.harness.FrameStatistics;
import cx.mia.lucent.laymark.core.harness.Measurement;
import cx.mia.lucent.laymark.core.harness.TimingChannel;

/**
 * The derived numbers a reader quotes, computed once while the samples are still in memory.
 *
 * <p>These exist so the samples can leave the result document. Inlined raw series made one
 * three-repetition scenario a 4.2 MB pretty-printed result; the samples now live beside the result
 * as {@code samples/*.jsonl.gz}, and everything downstream — scoring, printing, reports — reads
 * these summaries instead of re-deriving from series that may have been externalised.
 *
 * <p>The samples remain authoritative: a future reader who wants a different summary re-derives it
 * from the files. What must not happen is a summary silently diverging from its samples, which is
 * why these are computed at result construction and never recomputed after.
 *
 * @param gpu null when the machine could not measure the channel
 * @param millisPerChunkReceived null when the capture observed no chunk delivery
 */
public record PhaseSummaries(
        FrameStatistics interval,
        FrameStatistics renderCall,
        FrameStatistics submit,
        FrameStatistics gpu,
        long captureMillis,
        Double millisPerChunkReceived) {

    /** @return null when the measurement holds nothing to summarise */
    public static PhaseSummaries of(Measurement measurement) {
        if (measurement == null || !measurement.measured()) {
            return null;
        }
        return new PhaseSummaries(
                measurement.frameStatistics(),
                measurement.frameStatistics(TimingChannel.RENDER_CALL),
                measurement.frameStatistics(TimingChannel.SUBMIT),
                measurement.gpu().isEmpty() ? null : measurement.gpuStatistics(),
                measurement.captureMillis(),
                measurement.millisPerChunkReceived());
    }
}
