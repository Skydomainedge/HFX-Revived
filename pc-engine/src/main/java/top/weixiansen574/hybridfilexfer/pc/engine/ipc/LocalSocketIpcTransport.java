package top.weixiansen574.hybridfilexfer.pc.engine.ipc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import top.weixiansen574.hybridfilexfer.core.ipc.IpcJson;
import top.weixiansen574.hybridfilexfer.core.protocol.ProtocolConstants;

/**
 * Multi-client local IPC daemon transport supporting both {@code tcp://127.0.0.1:<port>}
 * (loopback + ephemeral {@code ipcToken}) and {@code unix://<path>} (JDK 17 AF_UNIX).
 *
 * <p>Uses direct {@link SocketChannel#read(ByteBuffer)} / {@link SocketChannel#write(ByteBuffer)}
 * for true full-duplex NDJSON framing without {@code Channels.newInputStream/newOutputStream}
 * {@code blockingLock()} contention. Atomically writes {@code engine.ipc.json} on startup and
 * removes it cleanly on {@link #close()} or JVM shutdown.</p>
 */
public class LocalSocketIpcTransport implements AutoCloseable {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /**
     * Metadata persisted atomically to {@code engine.ipc.json} for external thin shells / launchers.
     */
    public static final class IpcEndpointInfo {
        public String endpoint;
        public String transport; // "tcp" | "unix"
        public long pid;
        public String ipcToken;
        public int protocolVersion;
        public String engineVersion;
        public long startedAt;

        public IpcEndpointInfo() {
        }
    }

    private final JsonRpcDispatcher dispatcher;
    private final String requestedEndpoint;
    private final String ipcToken;
    private final Path infoFilePath;

    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicInteger clientIdSeq = new AtomicInteger(0);
    private final Set<SocketChannel> activeClients = ConcurrentHashMap.newKeySet();

    private volatile ServerSocketChannel serverChannel;
    private volatile String boundEndpoint;
    private volatile String transportKind;
    private volatile Path unixSocketPath;
    private volatile Thread acceptThread;
    private Thread shutdownHook;
    private volatile boolean inShutdownHook = false;

    public LocalSocketIpcTransport(JsonRpcDispatcher dispatcher,
                                   String requestedEndpoint,
                                   String ipcToken,
                                   Path infoFilePath) {
        if (dispatcher == null) {
            throw new IllegalArgumentException("dispatcher must not be null");
        }
        if (requestedEndpoint == null || requestedEndpoint.trim().isEmpty()) {
            throw new IllegalArgumentException("requestedEndpoint must not be empty");
        }
        this.dispatcher = dispatcher;
        this.requestedEndpoint = requestedEndpoint.trim();
        this.ipcToken = ipcToken;
        this.infoFilePath = infoFilePath != null ? infoFilePath : resolveDefaultInfoFilePath();
    }

    public static String generateRandomToken() {
        byte[] bytes = new byte[16];
        SECURE_RANDOM.nextBytes(bytes);
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }

    public static Path resolveDefaultInfoFilePath() {
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.trim().isEmpty()) {
            return Paths.get(localAppData.trim(), "HybridFileXfer", "engine.ipc.json");
        }
        String userHome = System.getProperty("user.home", ".");
        return Paths.get(userHome, ".hybridfilexfer", "engine.ipc.json");
    }

    public String getBoundEndpoint() {
        return boundEndpoint;
    }

    public String getIpcToken() {
        return ipcToken;
    }

    public Path getInfoFilePath() {
        return infoFilePath;
    }

    public int getBoundTcpPort() throws IOException {
        ServerSocketChannel ssc = this.serverChannel;
        if (ssc == null || !"tcp".equals(transportKind)) {
            return -1;
        }
        return ((InetSocketAddress) ssc.getLocalAddress()).getPort();
    }

    /**
     * Binds the local socket, atomically writes {@code engine.ipc.json}, and starts the accept loop.
     */
    public void start() throws IOException {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("LocalSocketIpcTransport already started");
        }
        try {
            bindServerSocket();
            writeInfoFileAtomically();
            registerShutdownHook();

            Thread t = new Thread(this::acceptLoop, "hfx-ipc-accept");
            t.setDaemon(false);
            this.acceptThread = t;
            t.start();
        } catch (IOException e) {
            close();
            throw e;
        }
    }

    /**
     * Waits until the accept loop terminates (i.e. after {@link #close()} or {@code engine.shutdown}).
     */
    public void awaitTermination() throws InterruptedException {
        Thread t = this.acceptThread;
        if (t != null) {
            t.join();
        }
        cleanupFiles();
    }

    private void bindServerSocket() throws IOException {
        if (requestedEndpoint.startsWith("tcp://")) {
            String hostPort = requestedEndpoint.substring("tcp://".length());
            int colon = hostPort.lastIndexOf(':');
            if (colon <= 0 || colon >= hostPort.length() - 1) {
                throw new IOException("Invalid tcp IPC endpoint (expected tcp://127.0.0.1:PORT): " + requestedEndpoint);
            }
            String host = hostPort.substring(0, colon);
            int port;
            try {
                port = Integer.parseInt(hostPort.substring(colon + 1));
            } catch (NumberFormatException e) {
                throw new IOException("Invalid tcp IPC port: " + requestedEndpoint, e);
            }
            InetAddress addr = InetAddress.getByName(host);
            if (!addr.isLoopbackAddress()) {
                throw new IOException("Refusing non-loopback TCP IPC bind address: " + host);
            }
            ServerSocketChannel ssc = ServerSocketChannel.open();
            ssc.bind(new InetSocketAddress(addr, port));
            int actualPort = ((InetSocketAddress) ssc.getLocalAddress()).getPort();
            this.serverChannel = ssc;
            this.transportKind = "tcp";
            this.boundEndpoint = "tcp://" + addr.getHostAddress() + ":" + actualPort;
        } else if (requestedEndpoint.startsWith("unix://")) {
            String rawPath = requestedEndpoint.substring("unix://".length());
            if (rawPath.isEmpty()) {
                throw new IOException("Empty unix socket path: " + requestedEndpoint);
            }
            // Handle unix:///C:/path on Windows gracefully
            if (rawPath.length() >= 3 && rawPath.charAt(0) == '/' && Character.isLetter(rawPath.charAt(1))
                    && rawPath.charAt(2) == ':') {
                rawPath = rawPath.substring(1);
            }
            Path sockPath = Paths.get(rawPath).toAbsolutePath();
            Path parent = sockPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.deleteIfExists(sockPath);
            ServerSocketChannel ssc = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
            ssc.bind(UnixDomainSocketAddress.of(sockPath));
            this.serverChannel = ssc;
            this.transportKind = "unix";
            this.unixSocketPath = sockPath;
            this.boundEndpoint = "unix://" + sockPath.toString().replace('\\', '/');
        } else {
            throw new IOException("Unsupported IPC endpoint scheme: " + requestedEndpoint);
        }
    }

    private void writeInfoFileAtomically() throws IOException {
        Path parent = infoFilePath.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        IpcEndpointInfo info = new IpcEndpointInfo();
        info.endpoint = this.boundEndpoint;
        info.transport = this.transportKind;
        info.pid = ProcessHandle.current().pid();
        info.ipcToken = this.ipcToken;
        info.protocolVersion = ProtocolConstants.VERSION_CODE;
        info.engineVersion = ProtocolConstants.ENGINE_VERSION;
        info.startedAt = System.currentTimeMillis();

        String json = IpcJson.toJson(info) + System.lineSeparator();
        Path tmpFile = parent.resolve(infoFilePath.getFileName().toString() + "." + info.pid + ".tmp");
        Files.writeString(tmpFile, json, StandardCharsets.UTF_8);
        try {
            Files.move(tmpFile, infoFilePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmpFile, infoFilePath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void acceptLoop() {
        ServerSocketChannel ssc = this.serverChannel;
        try {
            while (!closed.get() && ssc != null && ssc.isOpen()) {
                try {
                    SocketChannel client = ssc.accept();
                    if (client == null) {
                        continue;
                    }
                    client.configureBlocking(true);
                    activeClients.add(client);
                    int id = clientIdSeq.incrementAndGet();
                    Thread clientThread = new Thread(() -> serveClient(client), "hfx-ipc-client-" + id);
                    clientThread.setDaemon(true);
                    clientThread.start();
                } catch (IOException e) {
                    if (!closed.get()) {
                        continue;
                    }
                    break;
                }
            }
        } finally {
            cleanupFiles();
        }
    }

    private void serveClient(SocketChannel client) {
        final Object writeLock = new Object();
        final AtomicBoolean clientOpen = new AtomicBoolean(true);

        JsonRpcDispatcher.EventSink sink = ndjsonLine -> {
            if (!clientOpen.get() || closed.get()) {
                return;
            }
            try {
                writeNdjsonLine(client, writeLock, ndjsonLine);
            } catch (IOException e) {
                clientOpen.set(false);
            }
        };

        dispatcher.addSubscriber(sink);
        try {
            ChannelLineReader lineReader = new ChannelLineReader(client);
            String line;
            while (clientOpen.get() && !closed.get() && (line = lineReader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                JsonRpcDispatcher.DispatchResult result = dispatcher.dispatchLine(trimmed);
                writeNdjsonLine(client, writeLock, result.toJsonLine());
                if (result.shutdownRequested) {
                    Thread closer = new Thread(this::close, "hfx-ipc-shutdown");
                    closer.setDaemon(true);
                    closer.start();
                    break;
                }
            }
        } catch (IOException ignored) {
        } finally {
            clientOpen.set(false);
            dispatcher.removeSubscriber(sink);
            activeClients.remove(client);
            try {
                client.close();
            } catch (IOException ignored) {
            }
            if (closed.get()) {
                cleanupFiles();
            }
        }
    }

    public static void writeNdjsonLine(SocketChannel ch, Object writeLock, String line) throws IOException {
        byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        synchronized (writeLock) {
            while (buf.hasRemaining()) {
                int w = ch.write(buf);
                if (w < 0) {
                    throw new IOException("SocketChannel closed during write");
                }
                if (w == 0) {
                    Thread.yield();
                }
            }
        }
    }

    /**
     * Direct {@link SocketChannel} UTF-8 line reader that never locks {@code blockingLock()} against concurrent writes.
     */
    public static final class ChannelLineReader {
        private final SocketChannel channel;
        private final ByteBuffer readBuf = ByteBuffer.allocate(8192);
        private final ByteArrayOutputStream lineBuf = new ByteArrayOutputStream(256);
        private boolean eof = false;

        public ChannelLineReader(SocketChannel channel) {
            this.channel = channel;
            this.readBuf.flip();
        }

        public String readLine() throws IOException {
            lineBuf.reset();
            while (true) {
                while (readBuf.hasRemaining()) {
                    byte b = readBuf.get();
                    if (b == '\n') {
                        byte[] raw = lineBuf.toByteArray();
                        int len = raw.length;
                        if (len > 0 && raw[len - 1] == '\r') {
                            len--;
                        }
                        return new String(raw, 0, len, StandardCharsets.UTF_8);
                    }
                    lineBuf.write(b);
                }
                if (eof) {
                    if (lineBuf.size() > 0) {
                        return lineBuf.toString(StandardCharsets.UTF_8);
                    }
                    return null;
                }
                readBuf.clear();
                int n = channel.read(readBuf);
                if (n < 0) {
                    eof = true;
                    readBuf.flip();
                    if (lineBuf.size() > 0) {
                        return lineBuf.toString(StandardCharsets.UTF_8);
                    }
                    return null;
                }
                readBuf.flip();
            }
        }
    }

    private synchronized void registerShutdownHook() {
        if (shutdownHook != null) {
            return;
        }
        shutdownHook = new Thread(() -> {
            inShutdownHook = true;
            cleanupFiles();
        }, "hfx-ipc-file-cleanup");
        try {
            Runtime.getRuntime().addShutdownHook(shutdownHook);
        } catch (IllegalStateException ignored) {
            shutdownHook = null;
        }
    }

    private synchronized void unregisterShutdownHook() {
        if (shutdownHook != null && !inShutdownHook) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
            }
            shutdownHook = null;
        }
    }

    private void cleanupFiles() {
        deleteWithRetry(infoFilePath);
        Path sock = this.unixSocketPath;
        if (sock != null) {
            deleteWithRetry(sock);
        }
    }

    private static void deleteWithRetry(Path path) {
        if (path == null) {
            return;
        }
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                if (!Files.exists(path) || Files.deleteIfExists(path)) {
                    return;
                }
            } catch (IOException ignored) {
            }
            try {
                Thread.sleep(20L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Override
    public void close() {
        boolean firstClose = closed.compareAndSet(false, true);
        if (firstClose) {
            ServerSocketChannel ssc = this.serverChannel;
            if (ssc != null) {
                try {
                    ssc.close();
                } catch (IOException ignored) {
                }
            }
            for (SocketChannel client : activeClients) {
                try {
                    client.close();
                } catch (IOException ignored) {
                }
            }
            activeClients.clear();
            unregisterShutdownHook();
        }
        cleanupFiles();
    }
}
