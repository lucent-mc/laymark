package cx.mia.lucent.laymark.minecraft;

import cx.mia.lucent.laymark.core.harness.HarnessException;
import java.util.Locale;
import java.util.Map;

/**
 * Humane spellings for the few options whose codec-serialized form is unreadable.
 *
 * <p>The registry's value forms come from each option's codec, and three of them are hostile to a
 * hand-written config: {@code fov} serializes degrees as {@code (d - 70) / 40} (so 110° is
 * {@code 1.0}), {@code particles} is an id int ({@code 2} for minimal), and clouds live under
 * {@code renderClouds} with the legacy strings {@code "false"/"fast"/"true"}. This table lets the
 * config say {@code "fieldOfView": 110}, {@code "particles": "MINIMAL"}, {@code "clouds": "OFF"} —
 * translated to the codec form on the way in and back to the humane form on the way out, so
 * deviations report in the vocabulary the operator wrote.
 *
 * <p>Deliberately tiny and sugar-only: an unlisted key passes through untouched and stands or
 * falls with the registry, so this never becomes a second hand-kept option list.
 */
final class HumaneOptions {

    private HumaneOptions() {}

    /** A key and value literal in the form the game's registry accepts. */
    record GameForm(String key, String literal) {}

    private static final Map<String, String> PARTICLE_IDS =
            Map.of("ALL", "0", "DECREASED", "1", "MINIMAL", "2");
    private static final Map<String, String> CLOUD_LEGACY =
            Map.of("OFF", "false", "FAST", "fast", "FANCY", "true");

    /** The operator's spelling translated to the registry's key and codec literal. */
    static GameForm toGame(String key, String literal) {
        return switch (key) {
            case "fieldOfView" -> new GameForm("fov", degreesToCodec(literal));
            case "particles" ->
                    new GameForm("particles", named(PARTICLE_IDS, "particles", literal));
            case "clouds" -> new GameForm("renderClouds", named(CLOUD_LEGACY, "clouds", literal));
            default -> new GameForm(key, literal);
        };
    }

    /**
     * A codec literal read back from the game, in the form the operator would recognise.
     *
     * <p>{@code key} is the operator's spelling, not the registry's — the caller translates a
     * requested entry, reads through the registry, and hands the raw form back here.
     */
    static String fromGame(String key, String codecLiteral) {
        return switch (key) {
            case "fieldOfView" -> codecToDegrees(codecLiteral);
            case "particles" -> reverse(PARTICLE_IDS, codecLiteral);
            case "clouds" -> reverse(CLOUD_LEGACY, codecLiteral);
            default -> codecLiteral;
        };
    }

    private static String named(Map<String, String> table, String option, String literal) {
        String codec = table.get(literal.toUpperCase(Locale.ROOT));
        if (codec == null) {
            throw new HarnessException(
                    "'" + option + "' must be one of " + table.keySet().stream().sorted().toList()
                            + ", got '" + literal + "'");
        }
        return codec;
    }

    private static String reverse(Map<String, String> table, String codecLiteral) {
        return table.entrySet().stream()
                .filter(entry -> entry.getValue().equals(codecLiteral))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(codecLiteral);
    }

    private static String degreesToCodec(String literal) {
        double degrees;
        try {
            degrees = Double.parseDouble(literal.trim());
        } catch (NumberFormatException e) {
            throw new HarnessException("'fieldOfView' must be degrees, 30..110, got '" + literal + "'");
        }
        return String.valueOf((degrees - 70.0) / 40.0);
    }

    private static String codecToDegrees(String codecLiteral) {
        double normalized = Double.parseDouble(codecLiteral.trim());
        double degrees = normalized * 40.0 + 70.0;
        long rounded = Math.round(degrees);
        return degrees == rounded ? String.valueOf(rounded) : String.valueOf(degrees);
    }
}
