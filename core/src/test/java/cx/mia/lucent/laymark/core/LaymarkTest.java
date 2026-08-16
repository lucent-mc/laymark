package cx.mia.lucent.laymark.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tier 1: plain JUnit, no Minecraft, no loader, seconds rather than minutes.
 *
 * <p>Most iteration should live at this tier. It exists only while {@code core} stays pure,
 * which {@code purityCheck} enforces from the first commit.
 */
class LaymarkTest {

    @Test
    void identityIsStable() {
        assertEquals("laymark", Laymark.ID);
    }

    @Test
    void protocolVersionIsPositive() {
        assertTrue(Laymark.PROTOCOL_VERSION > 0, "protocol version is exact-matched at handshake");
    }

    @Test
    void launchFactPropertiesAreNamespaced() {
        assertTrue(Laymark.PROPERTY_PORT.startsWith(Laymark.ID + "."));
        assertTrue(Laymark.PROPERTY_TOKEN.startsWith(Laymark.ID + "."));
    }
}
