package top.weixiansen574.hybridfilexfer.core.transport;

import java.io.IOException;
import java.net.Socket;
import java.net.StandardSocketOptions;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

/**
 * Uniform high-throughput TCP socket tuning across PC and Android channels.
 */
public final class SocketTuner {
    public static final int DEFAULT_SOCKET_BUFFER_SIZE = 4 * 1024 * 1024; // 4 MB

    private SocketTuner() {
    }

    public static void tune(SocketChannel channel) {
        tune(channel, DEFAULT_SOCKET_BUFFER_SIZE);
    }

    public static void tune(SocketChannel channel, int bufferSizeBytes) {
        if (channel == null) {
            return;
        }
        int bufSize = Math.max(64 * 1024, bufferSizeBytes);
        Socket socket = null;
        try {
            socket = channel.socket();
        } catch (Throwable ignored) {
        }

        try {
            channel.setOption(StandardSocketOptions.TCP_NODELAY, Boolean.TRUE);
        } catch (Throwable t) {
            if (socket != null) {
                try {
                    socket.setTcpNoDelay(true);
                } catch (IOException ignored) {
                }
            }
        }

        try {
            channel.setOption(StandardSocketOptions.SO_KEEPALIVE, Boolean.TRUE);
        } catch (Throwable t) {
            if (socket != null) {
                try {
                    socket.setKeepAlive(true);
                } catch (IOException ignored) {
                }
            }
        }

        try {
            channel.setOption(StandardSocketOptions.SO_SNDBUF, bufSize);
        } catch (Throwable t) {
            if (socket != null) {
                try {
                    socket.setSendBufferSize(bufSize);
                } catch (IOException ignored) {
                }
            }
        }

        try {
            channel.setOption(StandardSocketOptions.SO_RCVBUF, bufSize);
        } catch (Throwable t) {
            if (socket != null) {
                try {
                    socket.setReceiveBufferSize(bufSize);
                } catch (IOException ignored) {
                }
            }
        }
    }

    public static void tuneServerSocket(ServerSocketChannel serverChannel) {
        tuneServerSocket(serverChannel, DEFAULT_SOCKET_BUFFER_SIZE);
    }

    public static void tuneServerSocket(ServerSocketChannel serverChannel, int receiveBufferBytes) {
        if (serverChannel == null) {
            return;
        }
        int bufSize = Math.max(64 * 1024, receiveBufferBytes);
        try {
            serverChannel.setOption(StandardSocketOptions.SO_REUSEADDR, Boolean.TRUE);
        } catch (Throwable t) {
            try {
                serverChannel.socket().setReuseAddress(true);
            } catch (IOException ignored) {
            }
        }
        try {
            serverChannel.setOption(StandardSocketOptions.SO_RCVBUF, bufSize);
        } catch (Throwable t) {
            try {
                serverChannel.socket().setReceiveBufferSize(bufSize);
            } catch (IOException ignored) {
            }
        }
    }
}
