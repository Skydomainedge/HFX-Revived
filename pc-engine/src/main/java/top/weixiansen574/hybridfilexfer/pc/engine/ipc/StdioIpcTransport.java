package top.weixiansen574.hybridfilexfer.pc.engine.ipc;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Sidecar NDJSON JSON-RPC 2.0 transport over {@code stdin}/{@code stdout}.
 *
 * <p>Guarantees that only single-line JSON-RPC 2.0 response and notification frames are
 * written to the output stream, serialized under a write lock so async events never
 * interleave with command responses.</p>
 */
public class StdioIpcTransport implements AutoCloseable {

    private final JsonRpcDispatcher dispatcher;
    private final InputStream inputStream;
    private final OutputStream outputStream;
    private final Object writeLock = new Object();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile BufferedWriter activeWriter;

    public StdioIpcTransport(JsonRpcDispatcher dispatcher, InputStream inputStream, OutputStream outputStream) {
        if (dispatcher == null || inputStream == null || outputStream == null) {
            throw new IllegalArgumentException("dispatcher, inputStream, and outputStream must not be null");
        }
        this.dispatcher = dispatcher;
        this.inputStream = inputStream;
        this.outputStream = outputStream;
    }

    /**
     * Runs the NDJSON read/dispatch loop on the calling thread until EOF or {@code engine.shutdown}.
     */
    public void runBlocking() throws IOException {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("StdioIpcTransport is already running");
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8));
        this.activeWriter = writer;

        JsonRpcDispatcher.EventSink sink = this::writeLineQuietly;
        dispatcher.addSubscriber(sink);
        try {
            String line;
            while (running.get() && (line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                JsonRpcDispatcher.DispatchResult result = dispatcher.dispatchLine(trimmed);
                writeLine(writer, result.toJsonLine());
                if (result.shutdownRequested) {
                    break;
                }
            }
        } finally {
            running.set(false);
            dispatcher.removeSubscriber(sink);
            this.activeWriter = null;
        }
    }

    private void writeLineQuietly(String line) {
        BufferedWriter writer = this.activeWriter;
        if (writer == null || !running.get()) {
            return;
        }
        try {
            writeLine(writer, line);
        } catch (IOException ignored) {
        }
    }

    private void writeLine(BufferedWriter writer, String line) throws IOException {
        synchronized (writeLock) {
            writer.write(line);
            writer.newLine();
            writer.flush();
        }
    }

    @Override
    public void close() {
        running.set(false);
    }
}
