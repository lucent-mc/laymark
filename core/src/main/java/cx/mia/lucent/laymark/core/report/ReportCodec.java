package cx.mia.lucent.laymark.core.report;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import cx.mia.lucent.laymark.core.harness.HarnessException;
import cx.mia.lucent.laymark.core.json.StrictEnum;
import cx.mia.lucent.laymark.core.stats.Band;

/**
 * The JSON summary layer.
 *
 * <p>Derived from raw samples like the Markdown is, not from each other. Two independent
 * renderings of one source can be cross-checked; a rendering of a rendering only ever agrees with
 * itself.
 */
public final class ReportCodec {

    private ReportCodec() {}

    private static final Gson GSON =
            new GsonBuilder()
                    .setPrettyPrinting()
                    .disableHtmlEscaping()
                    .serializeNulls()
                    .registerTypeAdapter(Band.class, StrictEnum.of(Band.class))
                    .create();

    public static String write(SelectionReport report) {
        return GSON.toJson(report);
    }

    /** @throws HarnessException if the document is malformed */
    public static SelectionReport read(String json) {
        if (json == null || json.isBlank()) {
            throw new HarnessException("report document is empty");
        }
        try {
            SelectionReport report = GSON.fromJson(json, SelectionReport.class);
            if (report == null) {
                throw new HarnessException("report document is empty");
            }
            return report;
        } catch (JsonParseException | IllegalStateException e) {
            throw new HarnessException("report document is not valid JSON: " + e.getMessage(), e);
        }
    }
}
