package cx.mia.lucent.laymark.core.scenario;

import cx.mia.lucent.laymark.core.plan.PlanException;

/**
 * A piece of scene geometry to place before measuring.
 *
 * <p>Names a Sponge Schematic v3 file — the actual cross-tool standard, which WorldEdit and FAWE
 * read and write and Litematica exports to. Entities are part of that format, so a whole scene is
 * one file rather than geometry plus a separate spawn list that could drift out of step with it.
 *
 * <p>The path is relative to the config that declared it, never absolute. A scenario config is
 * meant to be shared and re-run on another machine, and an absolute path is the fastest way to
 * make a benchmark that only works on the machine that wrote it.
 *
 * @param schematic relative path to the {@code .schem} file
 * @param origin block position the schematic's own origin maps to
 */
public record ScenePlacement(String schematic, int originX, int originY, int originZ) {

    public ScenePlacement {
        if (schematic == null || schematic.isBlank()) {
            throw new PlanException("a scene placement names no schematic");
        }
        if (schematic.startsWith("/") || schematic.startsWith("\\") || schematic.contains(":")) {
            throw new PlanException(
                    "scene path '" + schematic + "' must be relative to the config that declares it");
        }
        if (schematic.contains("..")) {
            // The path is resolved against a directory and then read. Refusing traversal here is
            // cheaper than reasoning about where a shared config could reach on someone else's disk.
            throw new PlanException("scene path '" + schematic + "' must not escape its directory");
        }
    }
}
