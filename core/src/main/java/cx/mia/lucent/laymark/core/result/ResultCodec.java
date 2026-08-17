package cx.mia.lucent.laymark.core.result;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import cx.mia.lucent.laymark.core.harness.HarnessException;

/**
 * Reads and writes {@link RunResult} as JSON.
 *
 * <p>Round-trippable, and tested that way, because the runner reads back what the harness wrote:
 * the two live in different processes, and the file is the only thing they agree on.
 *
 * <p>No custom adapters. Unlike a plan, a result contains no sealed hierarchy — deliberately.
 * Results outlive the code that wrote them, and a flat document stays readable by tools that never
 * heard of Laymark.
 */
public final class ResultCodec {

    private ResultCodec() {}

    private static final Gson GSON =
            new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create();

    /**
     * @implNote {@code serializeNulls} is on so an absent {@code failureReason} appears as an
     *     explicit null rather than vanishing. A reader can tell "no failure" from "field not
     *     written by this version" only if the field is always present.
     */
    public static String write(RunResult result) {
        return GSON.toJson(result);
    }

    /**
     * @throws HarnessException if the document is malformed or describes an invalid result
     */
    public static RunResult read(String json) {
        if (json == null || json.isBlank()) {
            throw new HarnessException("result document is empty");
        }
        try {
            RunResult result = GSON.fromJson(json, RunResult.class);
            if (result == null) {
                throw new HarnessException("result document is empty");
            }
            return result;
        } catch (JsonParseException | IllegalStateException e) {
            throw unwrap(e, "result document is not valid JSON");
        } catch (RuntimeException e) {
            throw unwrap(e, "result document could not be read");
        }
    }

    /** Gson rewraps whatever a record's compact constructor throws; recover the original. */
    private static HarnessException unwrap(RuntimeException thrown, String fallback) {
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            if (t instanceof HarnessException harnessException) {
                return harnessException;
            }
        }
        return new HarnessException(fallback + ": " + thrown.getMessage());
    }
}
