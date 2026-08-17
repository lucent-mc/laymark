package cx.mia.lucent.laymark.runner;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import cx.mia.lucent.laymark.runner.launch.LaunchException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Turns Spark's background profiler off before a run.
 *
 * <p><strong>It is enabled by default on the integrated server</strong>, which means an unmanaged
 * instance samples every thread continuously for the entire duration of every capture. That is a
 * constant tax on the thing being measured, and worse, it is a tax whose size scales with thread
 * count — that is, with the mod stack under test. It would move every number and appear in none of
 * them.
 *
 * <p>Written before launch rather than checked in-process, because by the time the game is up the
 * profiler has already started. The previous value is restored afterwards: the instance belongs to
 * someone who may well want it on.
 */
public final class SparkConfig {

    private SparkConfig() {}

    private static final String RELATIVE_PATH = "config/spark/config.json";
    private static final String KEY = "backgroundProfiler";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * Disables the background profiler.
     *
     * @return what the setting was, to be handed back to {@link #restore}. Empty when Spark is not
     *     installed, in which case nothing is written.
     */
    public static Boolean disableBackgroundProfiler(Path gameDirectory) {
        Path file = gameDirectory.resolve(RELATIVE_PATH);
        if (!Files.isRegularFile(file)) {
            // No Spark, or Spark has never run. Creating the file would be writing config for a mod
            // that may not exist, and an absent profiler cannot contaminate anything.
            return null;
        }
        JsonObject config = read(file);
        Boolean previous =
                config.has(KEY) && config.get(KEY).isJsonPrimitive()
                        ? config.get(KEY).getAsBoolean()
                        : null;

        if (Boolean.FALSE.equals(previous)) {
            return previous;
        }
        config.addProperty(KEY, false);
        write(file, config);
        System.out.println("disabled Spark's background profiler for this run");
        return previous;
    }

    /** Puts the operator's setting back. The instance is theirs, not Laymark's. */
    public static void restore(Path gameDirectory, Boolean previous) {
        if (previous == null) {
            return;
        }
        Path file = gameDirectory.resolve(RELATIVE_PATH);
        if (!Files.isRegularFile(file)) {
            return;
        }
        JsonObject config = read(file);
        config.addProperty(KEY, previous);
        write(file, config);
    }

    private static JsonObject read(Path file) {
        try {
            return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + file, e);
        } catch (RuntimeException e) {
            throw new LaunchException(file + " is not readable as JSON", e);
        }
    }

    private static void write(Path file, JsonObject config) {
        try {
            Files.writeString(file, GSON.toJson(config), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not write " + file, e);
        }
    }
}
