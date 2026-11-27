package top.weixiansen574.hybridfilexfer.core;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.Callable;

import top.weixiansen574.hybridfilexfer.core.callback.TransferFileCallback;
import top.weixiansen574.nio.DataByteChannel;

public class ReceiveFileCall implements Callable<Void> {
    private final WriteFileCall writeFileCall;
    private final int tIndex;
    private final DataByteChannel channel;
    private final TransferFileCallback callback;
    private final TransferConnection connection;
    private final String iName;
    private final int blockSize;
    private final Runnable onConnectionBreak;

    public ReceiveFileCall(int tIndex, TransferConnection connection, WriteFileCall writeFileCall,
                           TransferFileCallback callback) {
        this(tIndex, connection, writeFileCall, callback, FileBlock.DEFAULT_BLOCK_SIZE, null);
    }

    public ReceiveFileCall(int tIndex, TransferConnection connection, WriteFileCall writeFileCall,
                           TransferFileCallback callback, int blockSize) {
        this(tIndex, connection, writeFileCall, callback, blockSize, null);
    }

    public ReceiveFileCall(int tIndex, TransferConnection connection, WriteFileCall writeFileCall,
                           TransferFileCallback callback, int blockSize, Runnable onConnectionBreak) {
        this.writeFileCall = writeFileCall;
        this.tIndex = tIndex;
        this.connection = connection;
        this.channel = connection.channel;
        this.callback = callback;
        this.iName = connection.iName;
        this.blockSize = blockSize > 0 ? blockSize : FileBlock.DEFAULT_BLOCK_SIZE;
        this.onConnectionBreak = onConnectionBreak;
        connection.resetTotalTrafficInfo();
    }

    @Override
    public Void call() throws Exception {
        long startTime = System.currentTimeMillis();
        ByteBuffer heldBuffer = null;
        try {
            while (true) {
                short header = channel.readShort();
                switch (header) {
                    case TransferIdentifiers.FOLDER: {
                        int fileIndex = channel.readInt();
                        String path = channel.readUTF();
                        long lastModified = channel.readLong();
                        writeFileCall.putBlock(
                                new FileBlock(false, fileIndex, path, lastModified, 0, 0, blockSize, null),
                                tIndex);
                        break;
                    }
                    case TransferIdentifiers.FILE: {
                        int fileIndex = channel.readInt();
                        String path = channel.readUTF();
                        long lastModified = channel.readLong();
                        long totalSize = channel.readLong();
                        int index = channel.readInt();
                        int length = channel.readInt();
                        callback.onFileDownloading(iName, path,
                                ((long) index) * blockSize + length,
                                totalSize);
                        ByteBuffer buffer = writeFileCall.getBuffer();
                        heldBuffer = buffer;
                        buffer.clear();
                        if (length > buffer.capacity()) {
                            throw new IOException("Incoming block length " + length
                                    + " exceeds buffer capacity " + buffer.capacity());
                        }
                        buffer.limit(length);
                        while (buffer.hasRemaining()) {
                            int read = channel.read(buffer);
                            if (read == -1) {
                                throw new EOFException("Unexpected EOF while receiving block for " + path);
                            }
                            if (read > 0) {
                                connection.addDownloadedBytes(read);
                            }
                        }
                        FileBlock block = new FileBlock(true, fileIndex, path, lastModified,
                                totalSize, index, blockSize, buffer);
                        heldBuffer = null;
                        writeFileCall.putBlock(block, tIndex);
                        break;
                    }
                    case TransferIdentifiers.EOF:
                        writeFileCall.finishChannel(tIndex);
                        callback.onChannelComplete(iName,
                                connection.getTotalTraffic().downloadTraffic,
                                System.currentTimeMillis() - startTime);
                        return null;
                    case TransferIdentifiers.END_OF_INTERRUPTED:
                        writeFileCall.cancel();
                        callback.onChannelError(iName, TransferFileCallback.ERROR_TYPE_INTERRUPT, null);
                        return null;
                    case TransferIdentifiers.END_OF_READ_ERROR:
                        writeFileCall.cancel();
                        callback.onChannelError(iName, TransferFileCallback.ERROR_TYPE_READ_ERROR, null);
                        return null;
                    case TransferIdentifiers.END_OF_WRITE_ERROR:
                        writeFileCall.cancel();
                        callback.onChannelError(iName, TransferFileCallback.ERROR_TYPE_WRITE_ERROR, null);
                        return null;
                    default:
                        throw new IOException("Unknown transfer frame header: " + header);
                }
            }
        } catch (IOException e) {
            if (heldBuffer != null) {
                writeFileCall.recycleBuffer(heldBuffer);
            }
            writeFileCall.cancel();
            writeFileCall.finishChannel(tIndex);
            if (onConnectionBreak != null) {
                try {
                    onConnectionBreak.run();
                } catch (Throwable ignored) {
                }
            }
            callback.onChannelError(iName, TransferFileCallback.ERROR_TYPE_EXCEPTION, e.toString());
            throw e;
        }
    }
}
