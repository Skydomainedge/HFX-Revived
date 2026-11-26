package top.weixiansen574.hybridfilexfer;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.NotFoundException;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import top.weixiansen574.hybridfilexfer.aidl.IHfxEngine;
import top.weixiansen574.hybridfilexfer.aidl.IHfxEngineCallback;
import top.weixiansen574.hybridfilexfer.core.ipc.IpcEvents;
import top.weixiansen574.hybridfilexfer.core.ipc.IpcJson;
import top.weixiansen574.hybridfilexfer.core.ipc.IpcMethods;
import top.weixiansen574.hybridfilexfer.core.ipc.IpcPayloads;
import top.weixiansen574.hybridfilexfer.core.protocol.PairUriCodec;
import top.weixiansen574.hybridfilexfer.engine.HfxEngineService;

/**
 * Minimal offline CameraX + ZXing QR scanner (zero GMS dependency, zero business/network state).
 *
 * <p>Decodes {@code hfx://pair?...}, immediately dispatches {@code pair.submit_qr_uri} to
 * {@link IHfxEngine}, displays a lightweight animated recognition status for ~1.1s so the user
 * clearly perceives the result, and smoothly returns to {@link MainActivity}.</p>
 */
public class QrScanActivity extends AppCompatActivity implements ServiceConnection {

    private static final int REQUEST_CAMERA_PERMISSION = 201;
    private static final long REACTION_WINDOW_MS = 1150L;

    private PreviewView previewView;
    private QrScanOverlayView overlayView;
    private View statusCard;
    private TextView statusTitle;
    private TextView statusSubtitle;
    private TextView hintText;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean handledScan = new AtomicBoolean(false);
    private final AtomicBoolean invertNextFrame = new AtomicBoolean(false);
    private ExecutorService analysisExecutor;
    private QRCodeReader qrCodeReader;
    private Map<DecodeHintType, Object> decodeHints;
    private long lastInvalidToastMs = 0L;

    private IHfxEngine engine;
    private boolean engineBound = false;
    private String pendingUriToSubmit;

    private final IHfxEngineCallback engineCallback = new IHfxEngineCallback.Stub() {
        @Override
        public void onEvent(String eventName, String payloadJson) {
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (IpcEvents.PAIR_SUCCEEDED.equals(eventName)) {
                    IpcPayloads.PairSucceededPayload ok = IpcJson.fromJson(
                            payloadJson, IpcPayloads.PairSucceededPayload.class);
                    if (ok != null && statusSubtitle != null) {
                        statusSubtitle.setText(getString(R.string.qr_scan_pair_succeeded,
                                ok.peerName != null ? ok.peerName : ok.peerId));
                    }
                }
            });
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_qr_scan);

        previewView = findViewById(R.id.qr_preview_view);
        overlayView = findViewById(R.id.qr_scan_overlay);
        statusCard = findViewById(R.id.qr_status_card);
        statusTitle = findViewById(R.id.qr_status_title);
        statusSubtitle = findViewById(R.id.qr_status_subtitle);
        hintText = findViewById(R.id.qr_hint_text);
        findViewById(R.id.btn_qr_back).setOnClickListener(v -> finishWithTransition());

        qrCodeReader = new QRCodeReader();
        decodeHints = new EnumMap<>(DecodeHintType.class);
        decodeHints.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE));
        decodeHints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        analysisExecutor = Executors.newSingleThreadExecutor();

        bindEngineService();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.CAMERA},
                    REQUEST_CAMERA_PERMISSION);
        }
    }

    private void bindEngineService() {
        Intent intent = new Intent(this, HfxEngineService.class);
        engineBound = bindService(intent, this, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        engine = IHfxEngine.Stub.asInterface(service);
        try {
            engine.registerCallback(engineCallback);
        } catch (RemoteException ignored) {
        }
        String queued = this.pendingUriToSubmit;
        if (queued != null) {
            this.pendingUriToSubmit = null;
            submitUriToEngineAsync(queued);
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        engine = null;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                Toast.makeText(this, R.string.qr_scan_camera_permission_needed, Toast.LENGTH_LONG).show();
                finishWithTransition();
            }
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> providerFuture = ProcessCameraProvider.getInstance(this);
        providerFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = providerFuture.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                imageAnalysis.setAnalyzer(analysisExecutor, this::analyzeImageFrame);

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalysis);
            } catch (Exception e) {
                Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void analyzeImageFrame(@NonNull ImageProxy image) {
        try {
            if (handledScan.get()) {
                return;
            }
            if (image.getFormat() != ImageFormat.YUV_420_888 && image.getPlanes().length == 0) {
                return;
            }
            ImageProxy.PlaneProxy yPlane = image.getPlanes()[0];
            ByteBuffer buffer = yPlane.getBuffer();
            int width = image.getWidth();
            int height = image.getHeight();
            int rowStride = yPlane.getRowStride();

            byte[] yData = extractCompactYPlane(buffer, width, height, rowStride);
            PlanarYUVLuminanceSource source = new PlanarYUVLuminanceSource(
                    yData, width, height, 0, 0, width, height, false);

            Result decoded = tryDecode(source, false);
            if (decoded == null && invertNextFrame.getAndSet(!invertNextFrame.get())) {
                decoded = tryDecode(source, true);
            }
            if (decoded != null && decoded.getText() != null) {
                onRawQrDecoded(decoded.getText().trim());
            }
        } catch (Exception ignored) {
        } finally {
            image.close();
        }
    }

    private Result tryDecode(PlanarYUVLuminanceSource source, boolean inverted) {
        try {
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(
                    inverted ? source.invert() : source));
            return qrCodeReader.decode(bitmap, decodeHints);
        } catch (NotFoundException | com.google.zxing.ChecksumException | com.google.zxing.FormatException e) {
            return null;
        } finally {
            qrCodeReader.reset();
        }
    }

    private static byte[] extractCompactYPlane(ByteBuffer buffer, int width, int height, int rowStride) {
        byte[] out = new byte[width * height];
        buffer.rewind();
        if (rowStride == width) {
            buffer.get(out, 0, Math.min(out.length, buffer.remaining()));
            return out;
        }
        byte[] row = new byte[rowStride];
        for (int y = 0; y < height; y++) {
            int toRead = Math.min(rowStride, buffer.remaining());
            if (toRead <= 0) break;
            buffer.get(row, 0, toRead);
            System.arraycopy(row, 0, out, y * width, Math.min(width, toRead));
        }
        return out;
    }

    private void onRawQrDecoded(final String rawText) {
        final PairUriCodec.PairPayload parsed;
        try {
            parsed = PairUriCodec.decode(rawText);
        } catch (IllegalArgumentException invalid) {
            long now = System.currentTimeMillis();
            if (now - lastInvalidToastMs > 1800L) {
                lastInvalidToastMs = now;
                runOnUiThread(() -> {
                    overlayView.flashWarningThenResume();
                    hintText.setText(R.string.qr_scan_invalid_code);
                    mainHandler.postDelayed(() -> {
                        if (!handledScan.get() && !isFinishing()) {
                            hintText.setText(R.string.qr_scan_hint);
                        }
                    }, 1600L);
                });
            }
            return;
        }

        if (!handledScan.compareAndSet(false, true)) {
            return;
        }

        submitUriToEngineAsync(rawText);

        final String hostLabel = (parsed.hostName != null && !parsed.hostName.isEmpty())
                ? parsed.hostName : parsed.pcId;

        runOnUiThread(() -> {
            overlayView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            overlayView.setRecognizedSuccess();
            hintText.animate().alpha(0f).setDuration(180L).start();

            statusTitle.setText(getString(R.string.qr_scan_recognized_title, hostLabel));
            statusSubtitle.setText(R.string.qr_scan_recognized_subtitle);
            statusCard.setVisibility(View.VISIBLE);
            statusCard.setAlpha(0f);
            statusCard.setScaleX(0.88f);
            statusCard.setScaleY(0.88f);
            statusCard.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(240L)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();

            mainHandler.postDelayed(this::finishWithTransition, REACTION_WINDOW_MS);
        });
    }

    private void submitUriToEngineAsync(final String rawUri) {
        final IHfxEngine eng = this.engine;
        if (eng == null) {
            this.pendingUriToSubmit = rawUri;
            return;
        }
        new Thread(() -> {
            try {
                IpcPayloads.PairSubmitUriParams params = new IpcPayloads.PairSubmitUriParams();
                params.uri = rawUri;
                eng.executeCommand(IpcMethods.PAIR_SUBMIT_QR_URI, IpcJson.toJson(params));
            } catch (Exception ignored) {
            }
        }, "hfx-qr-submit").start();
    }

    private void finishWithTransition() {
        if (!isFinishing()) {
            finish();
            overridePendingTransition(R.anim.hfx_fade_in, R.anim.hfx_slide_out_right);
        }
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        overridePendingTransition(R.anim.hfx_fade_in, R.anim.hfx_slide_out_right);
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        if (analysisExecutor != null) {
            analysisExecutor.shutdownNow();
        }
        if (engine != null) {
            try {
                engine.unregisterCallback(engineCallback);
            } catch (RemoteException ignored) {
            }
            engine = null;
        }
        if (engineBound) {
            try {
                unbindService(this);
            } catch (IllegalArgumentException ignored) {
            }
            engineBound = false;
        }
        super.onDestroy();
    }
}
