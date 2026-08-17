package cx.mia.lucent.laymark.minecraft;

import cx.mia.lucent.laymark.core.harness.HarnessException;
import cx.mia.lucent.laymark.core.scenario.ScenePlacement;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.server.level.ServerLevel;

/**
 * Places scene geometry through vanilla APIs.
 *
 * <p><strong>Never through the command system.</strong> Commands are modifiable by mods, so
 * building a scene with {@code /fill} and {@code /summon} would let the very candidate under test
 * alter how the scene is constructed between arms — the two arms would then be measured on
 * different scenes while appearing to share a config.
 *
 * <p>Counts are verified against the file before any capture opens. A scene that placed 9,000 of
 * its 10,000 blocks looks exactly like a scene that placed all of them, and the missing tenth
 * would read as a performance difference.
 */
final class ScenePlacer {

    private ScenePlacer() {}

    /**
     * Block update flags: tell clients, and nothing else.
     *
     * <p>Neighbour updates are suppressed deliberately. Letting them run means sand falls, water
     * flows and redstone fires <em>while the scene is being built</em>, and the result depends on
     * the order blocks happened to be written — so the same file could settle into two different
     * scenes. {@code UPDATE_KNOWN_SHAPE} skips the shape recalculation for the same reason, and
     * suppressing drops stops a replaced block from littering the scene with items.
     */
    private static final int PLACEMENT_FLAGS =
            Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;

    /**
     * @param placement where the schematic's own (0,0,0) goes. The file's own {@code Offset} is
     *     deliberately not applied: an exporter may set it to anything, and placement has to be
     *     decided by the config that both arms share rather than by a field in the scene file.
     * @return how many blocks and entities were placed, for the caller to check against the file
     */
    static Placed place(ServerLevel level, Schematic schematic, ScenePlacement placement, Path file) {
        BlockPos origin =
                new BlockPos(placement.originX(), placement.originY(), placement.originZ());

        int blocks = 0;
        for (int y = 0; y < schematic.height(); y++) {
            for (int z = 0; z < schematic.length(); z++) {
                for (int x = 0; x < schematic.width(); x++) {
                    BlockState state = schematic.stateAt(x, y, z);
                    BlockPos at = origin.offset(x, y, z);
                    if (!level.setBlock(at, state, PLACEMENT_FLAGS)) {
                        throw new HarnessException(
                                "could not place " + state + " at " + at + " from " + file);
                    }
                    blocks++;
                }
            }
        }

        int entities = 0;
        for (CompoundTag tag : schematic.entities()) {
            if (spawn(level, tag, origin)) {
                entities++;
            }
        }
        return new Placed(blocks, entities);
    }

    /** What actually reached the world, so the caller can compare it with what the file declared. */
    record Placed(int blocks, int entities) {}

    /**
     * Spawns one entity from its tag.
     *
     * <p>Mobs are pinned: {@code NoAI} so they do not wander out of the scene mid-capture, and
     * {@code PersistenceRequired} so despawning does not quietly reduce the workload partway
     * through — either would let the two arms measure different numbers of entities.
     */
    private static boolean spawn(ServerLevel level, CompoundTag tag, BlockPos origin) {
        CompoundTag copy = tag.copy();
        // A UUID carried over from the export would collide the second time a scene is placed, and
        // every repetition places it again. Removing it makes the game mint a fresh one.
        copy.remove("UUID");

        Entity entity =
                EntityType.loadEntityRecursive(
                        copy, level, EntitySpawnReason.COMMAND, spawned -> spawned);
        if (entity == null) {
            return false;
        }

        double[] position = relativePosition(tag);
        entity.snapTo(
                origin.getX() + position[0],
                origin.getY() + position[1],
                origin.getZ() + position[2],
                entity.getYRot(),
                entity.getXRot());

        if (entity instanceof Mob mob) {
            mob.setNoAi(true);
            mob.setPersistenceRequired();
        }
        return level.addFreshEntity(entity);
    }

    /** Sponge stores entity positions relative to the schematic origin. */
    private static double[] relativePosition(CompoundTag tag) {
        var pos = tag.getList("Pos").orElse(null);
        if (pos == null || pos.size() < 3) {
            return new double[] {0, 0, 0};
        }
        return new double[] {
            pos.getDoubleOr(0, 0), pos.getDoubleOr(1, 0), pos.getDoubleOr(2, 0)
        };
    }
}
