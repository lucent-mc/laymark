package cx.mia.lucent.laymark.core.protocol;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/**
 * Newline-delimited frames over a pair of streams.
 *
 * <p>Deliberately stream-based rather than socket-based, so the framing can be exercised without
 * a socket at all and the socket layer has nothing left to get wrong.
 *
 * <p>{@link #readLine()} hands back the <strong>raw line</strong> rather than a decoded frame.
 * That is what makes "the wire format is the durable format" true rather than aspirational: the
 * runner appends exactly the bytes it received to {@code events.jsonl}. Decoding and re-encoding
 * would round-trip through a serializer whose output could drift, and the archived line would no
 * longer be the line that crossed the wire.
 *
 * <p>UTF-8 on both sides, explicitly. Relying on the platform default would let a run recorded on
 * one machine decode differently on another, and platform is already an experimental stratum.
 */
public final class FrameChannel implements AutoCloseable {

    private final BufferedReader reader;
    private final BufferedWriter writer;

    private FrameChannel(BufferedReader reader, BufferedWriter writer) {
        this.reader = reader;
        this.writer = writer;
    }

    public static FrameChannel over(InputStream in, OutputStream out) {
        return new FrameChannel(
                new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)),
                new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8)));
    }

    /** Writes one frame and flushes, because the peer is waiting on it. */
    public void send(Frame frame) throws IOException {
        writer.write(FrameCodec.encode(frame));
        writer.write('\n');
        writer.flush();
    }

    /** @return the raw line, or {@code null} at end of stream */
    public String readLine() throws IOException {
        return reader.readLine();
    }

    /**
     * @return the next decoded frame, or {@code null} at end of stream
     * @throws ProtocolException if the line is not a valid frame
     */
    public Frame receive() throws IOException {
        String line = readLine();
        return line == null ? null : FrameCodec.decode(line);
    }

    @Override
    public void close() throws IOException {
        try {
            writer.close();
        } finally {
            reader.close();
        }
    }
}
