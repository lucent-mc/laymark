package cx.mia.lucent.laymark.core.result;

import com.google.gson.Gson;
import cx.mia.lucent.laymark.core.harness.FrameSample;
import cx.mia.lucent.laymark.core.harness.GpuSample;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/**
 * Writes each phase's raw series to {@code samples/*.jsonl.gz} and strips them from the result.
 *
 * <p>The samples are authoritative and retained in full — that has not changed. What changes is
 * where: inlined into the result document they made one three-repetition scenario 4.2 MB of
 * pretty-printed JSON, read in full by anything that only wanted a mean. As gzipped JSON-lines
 * beside the result they cost a fraction of that, stream line by line, and leave the result
 * document holding the summaries plus a pointer.
 *
 * <p>One file per (scenario, repetition, pass, phase), named so a human can find the series a
 * number came from without opening anything.
 */
public final class SampleStore {

    private SampleStore() {}

    private static final Gson GSON = new Gson();

    /** Directory name under the run's output directory. */
    public static final String DIRECTORY = "samples";

    /** Writes every measured phase's series out and returns the result with pointers instead. */
    public static RunResult externalize(RunResult result, Path outputDirectory) {
        Path samplesDir = outputDirectory.resolve(DIRECTORY);
        List<ScenarioResult> rewritten = new ArrayList<>();

        for (ScenarioResult scenario : result.scenarios()) {
            List<PhaseResult> segments = new ArrayList<>();
            for (PhaseResult segment : scenario.segments()) {
                if (segment.measurement().frames().isEmpty()
                        && segment.measurement().gpu().isEmpty()) {
                    segments.add(segment);
                    continue;
                }
                String file =
                        DIRECTORY
                                + "/"
                                + scenario.scenarioId()
                                + "-r"
                                + scenario.repetition()
                                + "-"
                                + scenario.pass().toString().toLowerCase(java.util.Locale.ROOT)
                                + "-"
                                + segment.phase().toString().toLowerCase(java.util.Locale.ROOT)
                                + ".jsonl.gz";
                write(outputDirectory.resolve(file), segment);
                segments.add(segment.withSamplesAt(file));
            }
            rewritten.add(
                    new ScenarioResult(
                            scenario.scenarioId(),
                            scenario.repetition(),
                            scenario.pass(),
                            scenario.arrayPosition(),
                            scenario.outcome(),
                            scenario.failureReason(),
                            scenario.readback(),
                            scenario.flags(),
                            segments,
                            scenario.barrier(),
                            scenario.durationMillis()));
        }
        try {
            Files.createDirectories(samplesDir);
        } catch (IOException e) {
            throw new UncheckedIOException("could not create " + samplesDir, e);
        }
        return new RunResult(
                result.runId(),
                result.protocolVersion(),
                result.scenarioListRevision(),
                result.loadedMods(),
                rewritten,
                result.flags());
    }

    private static void write(Path file, PhaseResult segment) {
        try {
            Files.createDirectories(file.getParent());
            try (BufferedWriter out =
                    new BufferedWriter(
                            new OutputStreamWriter(
                                    new GZIPOutputStream(Files.newOutputStream(file)),
                                    StandardCharsets.UTF_8))) {
                for (FrameSample frame : segment.measurement().frames()) {
                    out.write(GSON.toJson(frame));
                    out.newLine();
                }
                for (GpuSample gpu : segment.measurement().gpu()) {
                    out.write(GSON.toJson(gpu));
                    out.newLine();
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not write samples to " + file, e);
        }
    }
}
