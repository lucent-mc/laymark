package cx.mia.lucent.laymark.core.harness;

import java.util.OptionalInt;

/**
 * The settings a scenario pins, as namespace → key → value literal.
 *
 * <p>There are no enumerated fields. The {@code minecraft} namespace reaches any option in the
 * game's own options registry by its {@code options.txt} key — parsed and applied through the
 * option's own codec, so an unknown key or unacceptable value fails naming the option instead of
 * silently running a different configuration. A handful of keys additionally accept a humane
 * spelling ({@code fieldOfView} in degrees, {@code particles}/{@code clouds} by name) where the
 * codec's serialized form is unreadable. Other namespaces are reserved for mods that expose
 * settings through a loader config API; they fail closed until a loader-side handler exists.
 *
 * <p>Anything absent is left at whatever the instance has, which is a deliberate choice rather
 * than an omission: a preset that tried to pin every option would break whenever a version added
 * one, and would fight mods whose entire purpose is to change rendering behaviour.
 *
 * <p>Values are kept as the literal the operator wrote. Typing is the receiving option's job — a
 * literal core tried to interpret would be a second parser disagreeing with the game's.
 *
 * <p>Window size is <strong>not</strong> a setting here; it is run-level configuration, held for
 * the whole experiment and reported in {@link PresetReadback}.
 */
public record Preset(java.util.Map<String, java.util.Map<String, String>> values) {

    /**
     * Vanilla's framerate slider treats its maximum as uncapped.
     *
     * <p>Not configurable. A framerate cap and vsync are <strong>mandatory, non-configurable
     * overrides</strong>: either one clamps frame time to something other than the work being
     * measured, which is exactly the difference a benchmark exists to find. A config that could
     * set them could quietly censor its own results.
     */
    public static final int UNLIMITED_FRAMERATE = 260;

    /** Vsync is forced off for the same reason, and likewise cannot be configured. */
    public static final boolean VSYNC = false;

    public Preset {
        java.util.Map<String, java.util.Map<String, String>> copied =
                new java.util.LinkedHashMap<>();
        if (values != null) {
            values.forEach(
                    (namespace, entries) -> {
                        if (namespace == null || namespace.isBlank()) {
                            throw new HarnessException("a settings namespace has a blank name");
                        }
                        copied.put(
                                namespace,
                                java.util.Collections.unmodifiableMap(
                                        new java.util.LinkedHashMap<>(entries)));
                    });
        }
        values = java.util.Collections.unmodifiableMap(copied);
    }

    /**
     * The pinned view distance, when this preset pins one.
     *
     * <p>Read from {@code minecraft.renderDistance}, because core needs this one value for itself:
     * the {@code all-in-radius} stop target, the pose-local chunk counter and Chunky's
     * pre-generation footprint are all derived from how far the game will send chunks.
     */
    public OptionalInt pinnedViewDistance() {
        String literal = values.getOrDefault("minecraft", java.util.Map.of()).get("renderDistance");
        if (literal == null) {
            return OptionalInt.empty();
        }
        try {
            return OptionalInt.of(Integer.parseInt(literal.trim()));
        } catch (NumberFormatException e) {
            throw new HarnessException(
                    "minecraft.renderDistance must be a whole number of chunks, got '" + literal
                            + "'");
        }
    }

    /**
     * The pinned view distance, required.
     *
     * @throws HarnessException when the preset does not pin one — the caller is deriving a chunk
     *     radius, and a radius taken from whatever the instance happens to have would make two
     *     machines, or two runs, do different amounts of work under one config's name
     */
    public int renderDistance() {
        return pinnedViewDistance()
                .orElseThrow(
                        () ->
                                new HarnessException(
                                        "the scenario's settings must pin minecraft.renderDistance;"
                                            + " the chunk target and the send radius are derived"
                                            + " from it, and a value inherited from the instance"
                                            + " would differ between machines"));
    }

    /** Key-by-key differences from another preset, one line each; empty when identical. */
    public java.util.List<String> describeDifferences(Preset other) {
        java.util.List<String> differences = new java.util.ArrayList<>();
        for (String namespace :
                java.util.stream.Stream.concat(
                                values.keySet().stream(), other.values.keySet().stream())
                        .distinct()
                        .toList()) {
            var mine = values.getOrDefault(namespace, java.util.Map.of());
            var theirs = other.values.getOrDefault(namespace, java.util.Map.of());
            for (String key :
                    java.util.stream.Stream.concat(mine.keySet().stream(), theirs.keySet().stream())
                            .distinct()
                            .toList()) {
                String mineValue = mine.getOrDefault(key, "(unset)");
                String theirsValue = theirs.getOrDefault(key, "(unset)");
                if (!mineValue.equals(theirsValue)) {
                    differences.add(namespace + ":" + key + ": " + mineValue + " vs " + theirsValue);
                }
            }
        }
        return java.util.List.copyOf(differences);
    }

    /** Pins nothing: every option stays at whatever the instance has. */
    public static Preset empty() {
        return new Preset(java.util.Map.of());
    }

    /** A preset pinning only the {@code minecraft} namespace, for tests and programmatic plans. */
    public static Preset ofMinecraft(java.util.Map<String, String> minecraft) {
        return new Preset(java.util.Map.of("minecraft", minecraft));
    }
}
