package cx.mia.lucent.laymark.minecraft;

import cx.mia.lucent.laymark.core.harness.HarnessException;
import java.util.Map;

/**
 * The parts of a Sponge schematic that are arithmetic rather than Minecraft.
 *
 * <p>Deliberately free of Minecraft types. Everything here — varint decoding, palette ordering,
 * whether the data fills the volume — is the logic that can be subtly wrong in a way that still
 * produces a plausible scene, and it is also the only part that can be tested without a game.
 * {@code SharedConstants} cannot even be class-loaded outside a mod loader, so a decoder that
 * touched Minecraft would be testable nowhere cheaper than a full launch.
 */
final class SchematicData {

    private SchematicData() {}

    /** LEB128 permits at most five bytes for a 32-bit value. */
    private static final int MAX_VARINT_SHIFT = 35;

    /**
     * Expands the varint-packed block data into palette ids.
     *
     * <p>Sponge stores one LEB128 varint per cell in Y-then-Z-then-X order, with no length prefix,
     * so the only integrity check available is that the stream yields exactly as many cells as the
     * dimensions promise. That is why it is not optional: a stream one byte short would otherwise
     * place a scene one cell smaller than the file describes.
     *
     * @param paletteSize ids must fall inside it; an out-of-range id is corruption, not air
     */
    static int[] decode(byte[] data, int expectedCells, int paletteSize, String source) {
        int[] ids = new int[expectedCells];
        int cells = 0;
        int cursor = 0;

        while (cursor < data.length) {
            int value = 0;
            int shift = 0;
            byte b;
            do {
                if (cursor >= data.length) {
                    throw new HarnessException(source + " block data ends mid-value");
                }
                b = data[cursor++];
                value |= (b & 0x7F) << shift;
                shift += 7;
                if (shift > MAX_VARINT_SHIFT) {
                    throw new HarnessException(source + " block data has a malformed varint");
                }
            } while ((b & 0x80) != 0);

            if (value < 0 || value >= paletteSize) {
                throw new HarnessException(
                        source + " block data references palette id " + value + " of " + paletteSize);
            }
            if (cells == expectedCells) {
                throw new HarnessException(
                        source + " declares " + expectedCells + " cells but its data holds more");
            }
            ids[cells++] = value;
        }

        if (cells != expectedCells) {
            throw new HarnessException(
                    source + " declares " + expectedCells + " cells but its data holds " + cells);
        }
        return ids;
    }

    /**
     * Orders a palette by its own integer ids.
     *
     * <p>The ids are not required to be contiguous or sorted in the file, so the result is sized to
     * the largest one. A gap is refused rather than filled: an id nothing maps to means the file
     * disagrees with itself, and choosing a block to stand in would invent geometry.
     *
     * @return each id's key, indexed by id
     */
    static String[] orderPalette(Map<String, Integer> palette, String source) {
        if (palette.isEmpty()) {
            throw new HarnessException(source + " has an empty block palette");
        }
        int highest = palette.values().stream().mapToInt(Integer::intValue).max().orElseThrow();
        if (highest < 0) {
            throw new HarnessException(source + " palette has negative ids");
        }

        String[] ordered = new String[highest + 1];
        for (Map.Entry<String, Integer> entry : palette.entrySet()) {
            int id = entry.getValue();
            if (id < 0) {
                throw new HarnessException(
                        source + " palette entry '" + entry.getKey() + "' has a negative id");
            }
            if (ordered[id] != null) {
                throw new HarnessException(
                        source + " palette gives id " + id + " to both '" + ordered[id] + "' and '"
                                + entry.getKey() + "'");
            }
            ordered[id] = entry.getKey();
        }
        for (int id = 0; id <= highest; id++) {
            if (ordered[id] == null) {
                throw new HarnessException(source + " palette has a gap at id " + id);
            }
        }
        return ordered;
    }

    /** Sponge's cell order, expressed once so nothing downstream has to know it. */
    static int index(int x, int y, int z, int width, int length) {
        return (y * length + z) * width + x;
    }
}
