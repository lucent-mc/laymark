package cx.mia.lucent.laymark.minecraft;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cx.mia.lucent.laymark.core.harness.HarnessException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The schematic logic that can be wrong without looking wrong.
 *
 * <p>Tier 1 because none of it touches Minecraft — which is not merely convenient. {@code
 * SharedConstants} cannot be class-loaded outside a mod loader, so anything on the far side of
 * that line is testable nowhere cheaper than a full game launch. Keeping the arithmetic on this
 * side is what makes these cases checkable at all.
 */
class SchematicDataTest {

    private static Map<String, Integer> palette(String... keys) {
        Map<String, Integer> palette = new LinkedHashMap<>();
        for (int id = 0; id < keys.length; id++) {
            palette.put(keys[id], id);
        }
        return palette;
    }

    @Test
    void decodesOneSingleByteVarintPerCell() {
        int[] ids = SchematicData.decode(new byte[] {0, 1, 1, 0}, 4, 2, "scene");
        assertArrayEquals(new int[] {0, 1, 1, 0}, ids);
    }

    /**
     * Any palette past 128 entries needs two-byte indices, which is the ordinary case for a real
     * scene. A decoder that only ever handled single bytes would read every one of them wrong and
     * still produce a scene that looked fine.
     */
    @Test
    void decodesMultiByteVarints() {
        // 130 as LEB128: continuation bit set on the low seven bits, then the remainder.
        int[] ids = SchematicData.decode(new byte[] {(byte) 0x82, 0x01}, 1, 200, "scene");
        assertEquals(130, ids[0]);
    }

    @Test
    void decodesTheLargestIdAPaletteCanReach() {
        int[] ids = SchematicData.decode(new byte[] {(byte) 0xFF, (byte) 0xFF, 0x03}, 1, 100_000, "s");
        assertEquals(65535, ids[0]);
    }

    /** A stream one byte short would otherwise place a scene one cell smaller than the file. */
    @Test
    void refusesDataThatDoesNotFillTheVolume() {
        HarnessException e =
                assertThrows(
                        HarnessException.class,
                        () -> SchematicData.decode(new byte[] {0, 0}, 4, 2, "scene"));
        assertTrue(e.getMessage().contains("4 cells"), e.getMessage());
        assertTrue(e.getMessage().contains("holds 2"), e.getMessage());
    }

    @Test
    void refusesDataThatOverfillsTheVolume() {
        HarnessException e =
                assertThrows(
                        HarnessException.class,
                        () -> SchematicData.decode(new byte[] {0, 0, 0, 0}, 2, 2, "scene"));
        assertTrue(e.getMessage().contains("more"), e.getMessage());
    }

    /** An id outside the palette is corruption. Treating it as air would invent a hole. */
    @Test
    void refusesAnIdThePaletteDoesNotHave() {
        HarnessException e =
                assertThrows(
                        HarnessException.class,
                        () -> SchematicData.decode(new byte[] {0, 7}, 2, 2, "scene"));
        assertTrue(e.getMessage().contains("palette id 7"), e.getMessage());
    }

    @Test
    void refusesATruncatedVarint() {
        // A continuation bit with nothing following it.
        assertThrows(
                HarnessException.class,
                () -> SchematicData.decode(new byte[] {(byte) 0x82}, 1, 200, "scene"));
    }

    @Test
    void refusesAVarintThatNeverTerminates() {
        byte[] runaway = {
            (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80
        };
        HarnessException e =
                assertThrows(
                        HarnessException.class,
                        () -> SchematicData.decode(runaway, 1, 200, "scene"));
        assertTrue(e.getMessage().contains("malformed varint"), e.getMessage());
    }

    @Test
    void ordersAPaletteByItsOwnIds() {
        Map<String, Integer> palette = new LinkedHashMap<>();
        palette.put("minecraft:dirt", 1);
        palette.put("minecraft:stone", 0);

        assertArrayEquals(
                new String[] {"minecraft:stone", "minecraft:dirt"},
                SchematicData.orderPalette(palette, "scene"),
                "ids decide the order, not the order the keys were written in");
    }

    /** An id nothing maps to means the file disagrees with itself. */
    @Test
    void refusesAPaletteWithAGap() {
        Map<String, Integer> palette = new LinkedHashMap<>();
        palette.put("minecraft:stone", 0);
        palette.put("minecraft:dirt", 2);

        HarnessException e =
                assertThrows(HarnessException.class, () -> SchematicData.orderPalette(palette, "scene"));
        assertTrue(e.getMessage().contains("gap at id 1"), e.getMessage());
    }

    @Test
    void refusesTwoBlocksSharingAnId() {
        Map<String, Integer> palette = new LinkedHashMap<>();
        palette.put("minecraft:stone", 0);
        palette.put("minecraft:dirt", 0);

        HarnessException e =
                assertThrows(HarnessException.class, () -> SchematicData.orderPalette(palette, "scene"));
        assertTrue(e.getMessage().contains("both"), e.getMessage());
    }

    @Test
    void refusesAnEmptyOrNegativePalette() {
        assertThrows(HarnessException.class, () -> SchematicData.orderPalette(Map.of(), "scene"));
        assertThrows(
                HarnessException.class,
                () -> SchematicData.orderPalette(Map.of("minecraft:stone", -1), "scene"));
    }

    /** Y, then Z, then X — getting this wrong rotates a scene without failing anything. */
    @Test
    void indexesCellsInSpongeOrder() {
        assertEquals(0, SchematicData.index(0, 0, 0, 2, 2));
        assertEquals(1, SchematicData.index(1, 0, 0, 2, 2));
        assertEquals(2, SchematicData.index(0, 0, 1, 2, 2));
        assertEquals(4, SchematicData.index(0, 1, 0, 2, 2));
        assertEquals(7, SchematicData.index(1, 1, 1, 2, 2));
    }

    @Test
    void paletteOrderingIsUsableAsDecodedIds() {
        String[] ordered = SchematicData.orderPalette(palette("a", "b", "c"), "scene");
        int[] ids = SchematicData.decode(new byte[] {2, 0, 1}, 3, ordered.length, "scene");
        assertEquals("c", ordered[ids[0]]);
        assertEquals("a", ordered[ids[1]]);
    }
}
