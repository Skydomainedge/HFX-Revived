package top.weixiansen574.hybridfilexfer.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;

import top.weixiansen574.hybridfilexfer.core.callback.TransferFileCallback;
import top.weixiansen574.nio.DataByteChannel;

/**
 * High-throughput block sender.
 *
 * <p>Coalesces all frame header fields into a reusable thread-private buffer and
 * writes {@code [headerBuf, payloadBuf]} via a single {@code writev} gathering syscall
 * ({@link DataByteChannel#writeFully(ByteBuffer[])}), reducing syscalls per block from
 * 9 down to 1 while guaranteeing full writes on partial-write backpressure.</p>
 */
public class SendFileCall implements Callable<Void> {
    private static final int INITIAL_HEADER_CAPACITY = 4096;

    private final ReadFileCall readFileCall;
    private final DataByteChannel channel;
    private final TransferConnection connection;
    private final TransferFileCallback callback;
    private final Runnable onConnectionBreak;
    private ByteBuffer headerBuf = ByteBuffer.allocate(INITIAL_HEADER_CAPACITY);
    private final ByteBuffer[] gatheringBuffers = new ByteBuffer[2];

    public SendFileCall(ReadFileCall readFileCall, TransferConnection connection, TransferFileCallback callback) {
        this(readFileCall, connection, callback, null);
    }

    public SendFileCall(ReadFileCall readFileCall, TransferConnection connection,
                        TransferFileCallback callback, Runnable onConnectionBreak) {
        this.readFileCall = readFileCall;
        this.connection = connection;
        this.channel = connection.channel;
        this.callback = callback;
        this.onConnectionBreak = onConnectionBreak;
        connection.resetTotalTrafficInfo();
    }

    private ByteBuffer ensureHeaderCapacity(int requiredBytes) {
        if (headerBuf.capacity() < requiredBytes) {
            int newCap = Math.max(requiredBytes, headerBuf.capacity() * 2);
            headerBuf = ByteBuffer.allocate(newCap);
        }
        headerBuf.clear();
        return headerBuf;
    }

    @Override
    public Void call() throws Exception {
        ByteBuffer heldBuffer = null;
        try {
            long startTime = System.currentTimeMillis();
            while (true) {
                FileBlock fileBlock = readFileCall.takeBlock();
                heldBuffer = fileBlock.data;
                if (fileBlock.fileIndex == -1) {
                    if (fileBlock == ReadFileCall.END_POINT) {
                        channel.writeShort(TransferIdentifiers.EOF);
                        callback.onChannelComplete(connection.iName,
                                connection.getTotalTraffic().uploadTraffic,
                                System.currentTimeMillis() - startTime);
                    } else if (fileBlock == ReadFileCall.INTERRUPT) {
                        channel.writeShort(TransferIdentifiers.END_OF_INTERRUPTED);
                        callback.onChannelError(connection.iName, TransferFileCallback.ERROR_TYPE_INTERRUPT, null);
                    } else if (fileBlock == ReadFileCall.READ_ERROR) {
                        channel.writeShort(TransferIdentifiers.END_OF_READ_ERROR);
                        callback.onChannelError(connection.iName, TransferFileCallback.ERROR_TYPE_READ_ERROR, null);
                    } else if (fileBlock == ReadFileCall.WRITE_ERROR) {
                        channel.writeShort(TransferIdentifiers.END_OF_WRITE_ERROR);
                        callback.onChannelError(connection.iName, TransferFileCallback.ERROR_TYPE_WRITE_ERROR, null);
                    }
                    heldBuffer = null;
                    break;
                }

                byte[] pathBytes = fileBlock.path.getBytes(StandardCharsets.UTF_8);
                if (pathBytes.length > 65535) {
                    throw new IOException("Transfer path exceeds UTF-16 length limit: " + pathBytes.length);
                }

                if (!fileBlock.isFile) {
                    int hdrLen = 2 + 4 + 2 + pathBytes.length + 8;
                    ByteBuffer hdr = ensureHeaderCapacity(hdrLen);
                    hdr.putShort(TransferIdentifiers.FOLDER);
                    hdr.putInt(fileBlock.fileIndex);
                    hdr.putShort((short) pathBytes.length);
                    hdr.put(pathBytes);
                    hdr.putLong(fileBlock.lastModified);
                    hdr.flip();
                    channel.writeFully(hdr);
                    heldBuffer = null;
                    continue;
                }

                int payloadLength = fileBlock.getLength();
                int hdrLen = 2 + 4 + 2 + pathBytes.length + 8 + 8 + 4 + 4;
                ByteBuffer hdr = ensureHeaderCapacity(hdrLen);
                hdr.putShort(TransferIdentifiers.FILE);
                hdr.putInt(fileBlock.fileIndex);
                hdr.putShort((short) pathBytes.length);
                hdr.put(pathBytes);
                hdr.putLong(fileBlock.lastModified);
                hdr.putLong(fileBlock.totalSize);
                hdr.putInt(fileBlock.index);
                hdr.putInt(payloadLength);
                hdr.flip();

                callback.onFileUploading(connection.iName, fileBlock.path,
                        fileBlock.getStartPosition() + payloadLength,
                        fileBlock.totalSize);

                ByteBuffer payload = fileBlock.data;
                payload.flip();
                if (payloadLength > 0) {
                    gatheringBuffers[0] = hdr;
                    gatheringBuffers[1] = payload;
                    channel.writeFully(gatheringBuffers);
                } else {
                    channel.writeFully(hdr);
                }
                readFileCall.recycleBuffer(payload);
                heldBuffer = null;
                connection.addUploadedBytes(payloadLength);
            }
        } catch (Exception e) {
            readFileCall.shutdownByConnectionBreak();
            if (heldBuffer != null) {
                readFileCall.recycleBuffer(heldBuffer);
            }
            if (onConnectionBreak != null) {
                try {
                    onConnectionBreak.run();
                } catch (Throwable ignored) {
                }
            }
            callback.onChannelError(connection.iName, TransferFileCallback.ERROR_TYPE_EXCEPTION, e.toString());
            throw e;
        }
        return null;
    }
}
