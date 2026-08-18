package cx.mia.lucent.laymark.core.harness;

/**
 * The graphics settings a scenario pins, expressed independently of Minecraft's option types.
 *
 * <p>Only settings that materially move frame time are here. Anything absent is left at whatever
 * the instance has, which is a deliberate choice rather than an omission: a preset that tried to
 * pin every option would break whenever a version added one, and would fight mods whose entire
 * purpose is to change rendering behaviour.
 *
 * <p>Window size is <strong>not</strong> a field. Resolution dominates GPU time, so it must be
 * held constant — but it is held constant by not touching it, and reported in
 * {@link PresetReadback}. Resizing a window mid-run is a change no arm asked for.
 *
 * <p>Neither is the named graphics preset — Fast, Fancy, Fabulous. Vanilla resolves those to
 * different underlying settings depending on GPU vendor and operating system, so two machines
 * asked for "Fancy" genuinely run different configurations while both truthfully reporting
 * "Fancy". That is a deviation readback cannot catch, which makes enumerating the settings the
 * only honest definition of a preset.
 *
 * @param renderDistance chunks
 * @param simulationDistance chunks
 * @param fieldOfView degrees; pinned because it decides how much geometry is on screen
 */
/*
 * @param options everything beyond the enumerated fields, as namespace → key → value. The
 *     "minecraft" namespace reaches any option in the game's own options registry by its
 *     options.txt key — graphicsPreset, gamma, cutoutLeaves, whatever exists — parsed and applied
 *     through the option's own codec, so an unknown key or unacceptable value fails instead of
 *     silently running a different configuration. Other namespaces are for mods that expose
 *     settings through a loader config API; they need a loader-side handler.
 */
public record Preset(
        int renderDistance,
        int simulationDistance,
        ParticleDetail particles,
        CloudDetail clouds,
        boolean entityShadows,
        int biomeBlendRadius,
        int fieldOfView,
        java.util.Map<String, java.util.Map<String, String>> options) {

    /**
     * Vanilla's framerate slider treats its maximum as uncapped.
     *
     * <p>Not a field on a preset. A framerate cap and vsync are <strong>mandatory,
     * non-configurable overrides</strong>: either one clamps frame time to something other than
     * the work being measured, which is exactly the difference a benchmark exists to find. A
     * config that could set them could quietly censor its own results.
     */
    public static final int UNLIMITED_FRAMERATE = 260;

    /** Vsync is forced off for the same reason, and likewise cannot be configured. */
    public static final boolean VSYNC = false;

    public enum ParticleDetail {
        ALL,
        DECREASED,
        MINIMAL
    }

    public enum CloudDetail {
        OFF,
        FAST,
        FANCY
    }

    public Preset {
        requireRange("renderDistance", renderDistance, 2, 32);
        requireRange("simulationDistance", simulationDistance, 5, 32);
        requireRange("biomeBlendRadius", biomeBlendRadius, 0, 7);
        requireRange("fieldOfView", fieldOfView, 30, 110);
        if (particles == null || clouds == null) {
            throw new HarnessException("preset has an unset enum field");
        }
        java.util.Map<String, java.util.Map<String, String>> copied =
                new java.util.LinkedHashMap<>();
        if (options != null) {
            options.forEach(
                    (namespace, values) ->
                            copied.put(namespace, java.util.Map.copyOf(values)));
        }
        options = java.util.Map.copyOf(copied);
    }

    private static void requireRange(String field, int value, int min, int max) {
        if (value < min || value > max) {
            // Out-of-range values are not clamped. Vanilla silently substitutes the default for an
            // unacceptable value, so clamping here would hide the substitution behind a number the
            // preset never asked for -- and every arm would inherit it equally, making it invisible.
            throw new HarnessException(
                    "preset " + field + " must be in [" + min + ", " + max + "], got " + value);
        }
    }

    /** Field-by-field differences from another preset, one line each; empty when identical. */
    public java.util.List<String> describeDifferences(Preset other) {
        java.util.List<String> differences = new java.util.ArrayList<>();
        difference(differences, "renderDistance", renderDistance, other.renderDistance);
        difference(differences, "simulationDistance", simulationDistance, other.simulationDistance);
        difference(differences, "particles", particles, other.particles);
        difference(differences, "clouds", clouds, other.clouds);
        difference(differences, "entityShadows", entityShadows, other.entityShadows);
        difference(differences, "biomeBlendRadius", biomeBlendRadius, other.biomeBlendRadius);
        difference(differences, "fieldOfView", fieldOfView, other.fieldOfView);
        for (String namespace :
                java.util.stream.Stream.concat(
                                options.keySet().stream(), other.options.keySet().stream())
                        .distinct()
                        .toList()) {
            var mine = options.getOrDefault(namespace, java.util.Map.of());
            var theirs = other.options.getOrDefault(namespace, java.util.Map.of());
            for (String key :
                    java.util.stream.Stream.concat(mine.keySet().stream(), theirs.keySet().stream())
                            .distinct()
                            .toList()) {
                difference(
                        differences,
                        namespace + ":" + key,
                        mine.getOrDefault(key, "(unset)"),
                        theirs.getOrDefault(key, "(unset)"));
            }
        }
        return java.util.List.copyOf(differences);
    }

    private static void difference(
            java.util.List<String> into, String field, Object mine, Object theirs) {
        if (!mine.equals(theirs)) {
            into.add(field + ": " + mine + " vs " + theirs);
        }
    }

    /** A neutral vanilla-shaped starting point, uncapped and with vsync off. */
    public static Preset defaults() {
        return new Preset(
                12,
                12,
                ParticleDetail.ALL,
                CloudDetail.FANCY,
                true,
                2,
                70,
                java.util.Map.of());
    }
}
