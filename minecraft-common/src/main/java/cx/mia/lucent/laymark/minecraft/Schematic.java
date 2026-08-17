package cx.mia.lucent.laymark.minecraft;

import cx.mia.lucent.laymark.core.harness.HarnessException;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A scene read from a Sponge Schematic v3 file.
 *
 * <p>Blocks are stored flat in the format's own index order — Y, then Z, then X — so
 * {@link #stateAt} is the only place that ordering is expressed and nothing downstream has to know
 * it.
 *
 * @param states one entry per cell, never null; a schematic's empty cells are explicit air
 * @param entities raw entity tags, positioned relative to the schematic origin
 */
public record Schematic(
        int width,
        int height,
        int length,
        int dataVersion,
        List<BlockState> states,
        List<CompoundTag> entities) {

    public Schematic {
        if (width <= 0 || height <= 0 || length <= 0) {
            throw new HarnessException(
                    "schematic has no volume: " + width + "x" + height + "x" + length);
        }
        states = List.copyOf(states);
        entities = entities == null ? List.of() : List.copyOf(entities);

        int expected = width * height * length;
        if (states.size() != expected) {
            throw new HarnessException(
                    "schematic declares " + expected + " cells but carries " + states.size());
        }
    }

    public int cellCount() {
        return width * height * length;
    }

    /** @param x within the schematic, not the world */
    public BlockState stateAt(int x, int y, int z) {
        return states.get(SchematicData.index(x, y, z, width, length));
    }
}
