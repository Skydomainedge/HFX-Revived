package top.weixiansen574.hybridfilexfer.pc.engine.discovery;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

import top.weixiansen574.hybridfilexfer.core.protocol.HmacAuth;
import top.weixiansen574.hybridfilexfer.core.protocol.ProtocolConstants;

/**
 * Pure-Java 24-bit RGB PNG QR code generator with zero {@code java.awt.*} / {@code javax.swing.*}
 * references to strictly preserve the headless engine zero-UI bytecode red line.
 *
 * <p>Generates crisp {@code 480x480} PNGs ({@code #000000} modules on {@code #FFFFFF} background,
 * {@link ErrorCorrectionLevel#M}, quiet zone >= 4 modules) under
 * {@code %TEMP%\hybridfilexfer\qr\pair_<shortId>.png} and tracks generated files for deterministic
 * cleanup on pairing completion, cancellation, timeout, engine shutdown, or JVM exit.</p>
 */
public class QrImageGenerator implements AutoCloseable {

    public static final int DEFAULT_SIZE_PX = ProtocolConstants.DEFAULT_QR_PNG_SIZE;
    public static final int MIN_SIZE_PX = 160;
    public static final int MAX_SIZE_PX = 2048;
    public static final int QUIET_ZONE_MODULES = 4;

    private static final byte[] PNG_SIGNATURE = new byte[]{
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path outputDir;
    private final Set<Path> trackedFiles = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean hookRegistered = new AtomicBoolean(false);
    private final Thread shutdownHook;

    public QrImageGenerator() {
        this(defaultQrTempDir());
    }

    public QrImageGenerator(Path outputDir) {
        this.outputDir = outputDir != null ? outputDir : defaultQrTempDir();
        this.shutdownHook = new Thread(this::cleanupAll, "hfx-qr-cleanup");
    }

    public static Path defaultQrTempDir() {
        String tmp = System.getProperty("java.io.tmpdir", ".");
        return Paths.get(tmp, "hybridfilexfer", "qr");
    }

    public Path getOutputDir() {
        return outputDir;
    }

    /**
     * Generates a 24-bit RGB PNG file for {@code content} and registers it for lifecycle cleanup.
     */
    public Path generatePngFile(String content, int requestedSizePx) throws IOException {
        int sizePx = clampSize(requestedSizePx);
        byte[] pngBytes = encodePngBytes(content, sizePx);

        Files.createDirectories(outputDir);
        ensureShutdownHook();

        byte[] idBytes = new byte[4];
        RANDOM.nextBytes(idBytes);
        String shortId = HmacAuth.toHex(idBytes);
        Path target = outputDir.resolve("pair_" + shortId + ".png").toAbsolutePath().normalize();

        Files.write(target, pngBytes,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        trackedFiles.add(target);
        return target;
    }

    /**
     * Encodes {@code content} as a standard 24-bit RGB PNG byte array without touching {@code java.awt}.
     */
    public byte[] encodePngBytes(String content, int requestedSizePx) throws IOException {
        if (content == null || content.isEmpty()) {
            throw new IllegalArgumentException("QR content must not be empty");
        }
        int sizePx = clampSize(requestedSizePx);
        BitMatrix matrix = encodeMatrix(content, sizePx);
        return writeRgbPng(matrix, sizePx, sizePx);
    }

    /**
     * Deletes a single QR PNG file and removes it from tracking.
     */
    public boolean deleteQrFile(Path path) {
        if (path == null) {
            return false;
        }
        Path normalized = path.toAbsolutePath().normalize();
        trackedFiles.remove(normalized);
        try {
            return Files.deleteIfExists(normalized);
        } catch (IOException ignored) {
            return false;
        }
    }

    /**
     * Deletes a single QR PNG file by string path.
     */
    public boolean deleteQrFile(String path) {
        if (path == null || path.trim().isEmpty()) {
            return false;
        }
        return deleteQrFile(Paths.get(path.trim()));
    }

    /**
     * Deletes all QR PNG files created by this generator instance.
     */
    public void cleanupAll() {
        for (Path p : trackedFiles.toArray(new Path[0])) {
            deleteQrFile(p);
        }
    }

    public int getTrackedFileCount() {
        return trackedFiles.size();
    }

    @Override
    public void close() {
        cleanupAll();
        if (hookRegistered.compareAndSet(true, false)) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
            }
        }
    }

    private void ensureShutdownHook() {
        if (hookRegistered.compareAndSet(false, true)) {
            try {
                Runtime.getRuntime().addShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                hookRegistered.set(false);
            }
        }
    }

    private static int clampSize(int requested) {
        if (requested <= 0) {
            return DEFAULT_SIZE_PX;
        }
        return Math.max(MIN_SIZE_PX, Math.min(MAX_SIZE_PX, requested));
    }

    private static BitMatrix encodeMatrix(String content, int sizePx) throws IOException {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        hints.put(EncodeHintType.MARGIN, QUIET_ZONE_MODULES);
        hints.put(EncodeHintType.CHARACTER_SET, StandardCharsets.UTF_8.name());
        try {
            return new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints);
        } catch (WriterException e) {
            throw new IOException("Failed to encode QR matrix: " + e.getMessage(), e);
        }
    }

    /**
     * Pure-Java RFC 2083 24-bit RGB PNG encoder using {@link Deflater} and {@link CRC32}.
     */
    private static byte[] writeRgbPng(BitMatrix matrix, int width, int height) throws IOException {
        int mw = matrix.getWidth();
        int mh = matrix.getHeight();
        int rowStride = 1 + width * 3;
        byte[] rawScanlines = new byte[rowStride * height];

        for (int y = 0; y < height; y++) {
            int rowOffset = y * rowStride;
            rawScanlines[rowOffset] = 0; // PNG filter type 0 (None)
            int srcY = (mh == height) ? y : (int) (((long) y * mh) / height);
            for (int x = 0; x < width; x++) {
                int srcX = (mw == width) ? x : (int) (((long) x * mw) / width);
                boolean dark = matrix.get(srcX, srcY);
                byte v = dark ? (byte) 0x00 : (byte) 0xFF;
                int px = rowOffset + 1 + x * 3;
                rawScanlines[px] = v;
                rawScanlines[px + 1] = v;
                rawScanlines[px + 2] = v;
            }
        }

        byte[] compressed = zlibCompress(rawScanlines);

        ByteArrayOutputStream png = new ByteArrayOutputStream(compressed.length + 128);
        DataOutputStream dos = new DataOutputStream(png);
        dos.write(PNG_SIGNATURE);

        // IHDR: 13 bytes
        ByteArrayOutputStream ihdr = new ByteArrayOutputStream(13);
        DataOutputStream ihdrOut = new DataOutputStream(ihdr);
        ihdrOut.writeInt(width);
        ihdrOut.writeInt(height);
        ihdrOut.writeByte(8); // bit depth = 8
        ihdrOut.writeByte(2); // color type = 2 (Truecolor 24-bit RGB)
        ihdrOut.writeByte(0); // compression method = deflate
        ihdrOut.writeByte(0); // filter method = adaptive
        ihdrOut.writeByte(0); // interlace = none
        writeChunk(dos, "IHDR", ihdr.toByteArray());

        // IDAT
        writeChunk(dos, "IDAT", compressed);

        // IEND
        writeChunk(dos, "IEND", new byte[0]);
        dos.flush();
        return png.toByteArray();
    }

    private static byte[] zlibCompress(byte[] raw) {
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
        try {
            deflater.setInput(raw);
            deflater.finish();
            ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.max(256, raw.length / 8));
            byte[] buf = new byte[4096];
            while (!deflater.finished()) {
                int n = deflater.deflate(buf);
                if (n > 0) {
                    baos.write(buf, 0, n);
                } else if (deflater.needsInput()) {
                    break;
                }
            }
            return baos.toByteArray();
        } finally {
            deflater.end();
        }
    }

    private static void writeChunk(DataOutputStream out, String type, byte[] data) throws IOException {
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        out.writeInt(data.length);
        out.write(typeBytes);
        if (data.length > 0) {
            out.write(data);
        }
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        if (data.length > 0) {
            crc.update(data);
        }
        out.writeInt((int) crc.getValue());
    }
}
