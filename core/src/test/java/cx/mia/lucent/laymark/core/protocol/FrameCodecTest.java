package cx.mia.lucent.laymark.core.protocol;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cx.mia.lucent.laymark.core.Phase;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Tier 1: no Minecraft, no loader, no socket. Milliseconds. */
class FrameCodecTest {

    static Stream<Frame> everyFrameType() {
        return Stream.of(
                new Frame.Hello("nonce-abc", 1, 4242L),
                new Frame.Heartbeat(1_234_567_890L),
                new Frame.ScenarioStarted("chunkgen-high-altitude", 3),
                new Frame.PhaseEntered("chunkgen-high-altitude", Phase.UNGENERATED_TRAVERSAL, 99L),
                new Frame.ScenarioFinished("entity-culling", true, ""),
                new Frame.ScenarioFinished("entity-culling", false, "throttle fired mid-capture"),
                new Frame.RunFailed("preset-drift", "renderDistance read back as 12, expected 32"),
                new Frame.RunFinished("runs/abc123/result.json"));
    }

    @ParameterizedTest
    @MethodSource("everyFrameType")
    void roundTrips(Frame original) {
        assertEquals(original, FrameCodec.decode(FrameCodec.encode(original)));
    }

    @ParameterizedTest
    @MethodSource("everyFrameType")
    void encodesToASingleLine(Frame frame) {
        String encoded = FrameCodec.encode(frame);
        assertFalse(encoded.contains("\n"), "a frame must occupy exactly one NDJSON line");
        assertTrue(encoded.startsWith("{\"type\":"), "discriminator leads, so a truncated line is still diagnosable");
    }

    /**
     * The registry is hand-maintained, so this asserts it covers the sealed hierarchy. Adding a
     * frame type and forgetting to register it would otherwise serialize fine on one side and
     * fail to parse on the other -- exactly the sort of failure that only shows up mid-run.
     */
    @Test
    void registryCoversEveryPermittedSubclass() {
        List<String> unregistered =
                Stream.of(Frame.class.getPermittedSubclasses())
                        .filter(
                                type -> {
                                    try {
                                        Frame sample = sampleOf(type);
                                        FrameCodec.encode(sample);
                                        return false;
                                    } catch (ProtocolException e) {
                                        return true;
                                    }
                                })
                        .map(Class::getSimpleName)
                        .toList();

        assertTrue(
                unregistered.isEmpty(),
                "unregistered frame types: " + unregistered + " -- add them to FrameCodec");
    }

    private static Frame sampleOf(Class<?> type) {
        return everyFrameType()
                .filter(f -> f.getClass().equals(type))
                .findFirst()
                .orElseThrow(
                        () ->
                                new AssertionError(
                                        type.getSimpleName()
                                                + " has no sample in everyFrameType(); this test"
                                                + " cannot vouch for it"));
    }

    @Test
    void rejectsUnknownType() {
        ProtocolException e =
                assertThrows(
                        ProtocolException.class,
                        () -> FrameCodec.decode("{\"type\":\"teleport-to-mars\"}"));
        assertTrue(e.getMessage().contains("teleport-to-mars"));
        assertTrue(e.getMessage().contains("known types"), "the error should say what is valid");
    }

    @Test
    void rejectsFramesWithoutADiscriminator() {
        assertThrows(ProtocolException.class, () -> FrameCodec.decode("{\"pid\":1}"));
    }

    /** Every malformed line leaves as a {@link ProtocolException}, never a raw runtime type. */
    @Test
    void rejectsDiscriminatorsThatAreNotStrings() {
        assertAll(
                () -> assertThrows(ProtocolException.class, () -> FrameCodec.decode("{\"type\":null}")),
                () -> assertThrows(ProtocolException.class, () -> FrameCodec.decode("{\"type\":{}}")),
                () -> assertThrows(ProtocolException.class, () -> FrameCodec.decode("{\"type\":[1,2]}")),
                () -> assertThrows(ProtocolException.class, () -> FrameCodec.decode(null)));
    }

    @Test
    void rejectsNonObjects() {
        assertAll(
                () -> assertThrows(ProtocolException.class, () -> FrameCodec.decode("not json")),
                () -> assertThrows(ProtocolException.class, () -> FrameCodec.decode("[1,2,3]")),
                () -> assertThrows(ProtocolException.class, () -> FrameCodec.decode("")));
    }

    /** A string carrying a newline must not break the framing. */
    @Test
    void survivesNewlinesInsidePayloads() {
        Frame original = new Frame.RunFailed("crash", "line one\nline two\r\nline three");
        String encoded = FrameCodec.encode(original);
        assertFalse(encoded.contains("\n"), "the encoder must escape newlines, not emit them");
        assertEquals(original, FrameCodec.decode(encoded));
    }
}
