package cx.mia.lucent.laymark.minecraft;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import cx.mia.lucent.laymark.core.harness.HarnessException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Reads Sponge Schematic v3 files.
 *
 * <p>Sponge v3 rather than a Laymark format because it is the actual cross-tool standard —
 * WorldEdit and FAWE read and write it, Litematica exports to it — so a scene can be built with
 * the tools people already use. Parsing costs nothing extra: {@code minecraft-common} already
 * imports Minecraft, so {@code NbtIo} and {@code BlockStateParser} suffice and no third-party
 * dependency enters the process being measured.
 *
 * <p>Every failure here is hard. A missing palette entry is not substituted with air, a version
 * mismatch is not fixed up hopefully, and a truncated block array is not padded — a scene that is
 * quietly not the scene the file describes produces a benchmark of something nobody configured,
 * and there is nothing in the numbers afterwards to reveal it.
 */
final class SchematicReader {

    private SchematicReader() {}

    /** Sponge Schematic version this reader understands. */
    private static final int SUPPORTED_VERSION = 3;

    /** A scene is scenery, not a world save; anything approaching this is a mistake. */
    private static final long NBT_QUOTA_BYTES = 64L * 1024 * 1024;

    static Schematic read(Path file, HolderLookup<Block> blocks) {
        if (!Files.isRegularFile(file)) {
            throw new HarnessException("no schematic at " + file);
        }

        CompoundTag root;
        try {
            root = NbtIo.readCompressed(file, NbtAccounter.create(NBT_QUOTA_BYTES));
        } catch (IOException e) {
            throw new HarnessException("could not read schematic " + file, e);
        }

        // v3 moved everything under a "Schematic" wrapper; v2 had it at the root. Requiring the
        // wrapper is how a v2 file is caught, since its fields would otherwise read as absent.
        CompoundTag schematic =
                root.getCompound("Schematic")
                        .orElseThrow(
                                () ->
                                        new HarnessException(
                                                file
                                                        + " has no 'Schematic' tag; Sponge v2 and"
                                                        + " earlier are not supported"));

        int version = schematic.getIntOr("Version", 0);
        if (version != SUPPORTED_VERSION) {
            throw new HarnessException(
                    file + " is Sponge Schematic v" + version + "; this build reads v"
                            + SUPPORTED_VERSION);
        }

        int dataVersion = schematic.getIntOr("DataVersion", 0);
        int gameDataVersion = SharedConstants.getCurrentVersion().dataVersion().version();
        if (dataVersion != gameDataVersion) {
            // Refused rather than run through the data fixers. A fixed-up scene is a different
            // scene, and two arms comparing against a file that upgrades differently would be
            // comparing two worlds. Re-export from the target version instead.
            throw new HarnessException(
                    file + " was written for data version " + dataVersion + " but this game is "
                            + gameDataVersion
                            + "; re-export the scene rather than letting it be converted");
        }

        int width = schematic.getShortOr("Width", (short) 0) & 0xFFFF;
        int height = schematic.getShortOr("Height", (short) 0) & 0xFFFF;
        int length = schematic.getShortOr("Length", (short) 0) & 0xFFFF;

        CompoundTag container =
                schematic
                        .getCompound("Blocks")
                        .orElseThrow(() -> new HarnessException(file + " has no 'Blocks' container"));

        List<BlockState> palette = palette(container, blocks, file);
        List<BlockState> states = decode(container, palette, width * height * length, file);

        return new Schematic(width, height, length, dataVersion, states, entities(schematic));
    }

    /**
     * Resolves the palette to real block states.
     *
     * <p>Indexed by the palette's own integer ids, which are not required to be contiguous or
     * ordered, so the list is sized to the largest id rather than to the entry count.
     */
    private static List<BlockState> palette(
            CompoundTag container, HolderLookup<Block> lookup, Path file) {
        CompoundTag palette =
                container.getCompound("Palette")
                        .orElseThrow(() -> new HarnessException(file + " has no block palette"));

        Map<String, Integer> ids = new LinkedHashMap<>();
        for (String key : palette.keySet()) {
            ids.put(key, palette.getIntOr(key, -1));
        }

        List<BlockState> states = new ArrayList<>();
        for (String key : SchematicData.orderPalette(ids, file.toString())) {
            try {
                states.add(BlockStateParser.parseForBlock(lookup, key, false).blockState());
            } catch (CommandSyntaxException e) {
                // A block the game does not know is usually a scene exported from a modded world
                // and run against a stack without that mod. Substituting air would silently change
                // the geometry being measured.
                throw new HarnessException(
                        file + " palette entry '" + key + "' is not a block this game knows", e);
            }
        }
        return states;
    }

    /**
     * Expands the varint-packed block data.
     *
     * <p>Sponge stores one LEB128 varint per cell, in Y-then-Z-then-X order. The encoding is
     * length-prefixed by nothing, so the only check available is that the stream yields exactly as
     * many cells as the dimensions promise — which is why that check is not optional.
     */
    private static List<BlockState> decode(
            CompoundTag container, List<BlockState> palette, int expected, Path file) {
        byte[] data =
                container.getByteArray("Data")
                        .orElseThrow(() -> new HarnessException(file + " has no block data"));

        int[] ids = SchematicData.decode(data, expected, palette.size(), file.toString());
        List<BlockState> states = new ArrayList<>(expected);
        for (int id : ids) {
            states.add(palette.get(id));
        }
        return states;
    }

    private static List<CompoundTag> entities(CompoundTag schematic) {
        List<CompoundTag> entities = new ArrayList<>();
        ListTag list = schematic.getList("Entities").orElse(null);
        if (list == null) {
            return entities;
        }
        for (Tag tag : list) {
            tag.asCompound().ifPresent(entities::add);
        }
        return entities;
    }
}
