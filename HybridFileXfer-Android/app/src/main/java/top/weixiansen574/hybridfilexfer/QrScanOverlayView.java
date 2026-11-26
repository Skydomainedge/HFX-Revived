package top.weixiansen574.hybridfilexfer;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.Nullable;

/**
 * Lightweight custom viewfinder overlay for {@link QrScanActivity}: dimmed scrim, rounded
 * corner brackets, smooth animated scanning beam, and color transition on QR recognition.
 */
public class QrScanOverlayView extends View {

    private static final int COLOR_SCANNING = Color.WHITE;
    private static final int COLOR_SUCCESS = 0xFF22C55E;
    private static final int COLOR_WARNING = 0xFFF59E0B;

    private final Paint scrimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cornerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint laserPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF frameRect = new RectF();

    private ValueAnimator scanLineAnimator;
    private ValueAnimator colorAnimator;
    private float scanProgress = 0f;
    private boolean scanningActive = true;
    private int currentAccentColor = COLOR_SCANNING;

    public QrScanOverlayView(Context context) {
        this(context, null);
    }

    public QrScanOverlayView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public QrScanOverlayView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        float density = getResources().getDisplayMetrics().density;

        scrimPaint.setColor(0x99000000);
        scrimPaint.setStyle(Paint.Style.FILL);

        borderPaint.setColor(0x55FFFFFF);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(1.2f * density);

        cornerPaint.setColor(currentAccentColor);
        cornerPaint.setStyle(Paint.Style.STROKE);
        cornerPaint.setStrokeWidth(3.5f * density);
        cornerPaint.setStrokeCap(Paint.Cap.ROUND);

        laserPaint.setStyle(Paint.Style.FILL);
        initScanAnimator();
    }

    private void initScanAnimator() {
        scanLineAnimator = ValueAnimator.ofFloat(0.06f, 0.94f);
        scanLineAnimator.setDuration(1900L);
        scanLineAnimator.setRepeatCount(ValueAnimator.INFINITE);
        scanLineAnimator.setRepeatMode(ValueAnimator.REVERSE);
        scanLineAnimator.setInterpolator(new LinearInterpolator());
        scanLineAnimator.addUpdateListener(anim -> {
            scanProgress = (Float) anim.getAnimatedValue();
            postInvalidateOnAnimation();
        });
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (scanningActive && scanLineAnimator != null && !scanLineAnimator.isStarted()) {
            scanLineAnimator.start();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (scanLineAnimator != null) {
            scanLineAnimator.cancel();
        }
        if (colorAnimator != null) {
            colorAnimator.cancel();
        }
        super.onDetachedFromWindow();
    }

    public void setRecognizedSuccess() {
        scanningActive = false;
        if (scanLineAnimator != null) {
            scanLineAnimator.cancel();
        }
        animateAccentColor(COLOR_SUCCESS);
    }

    public void flashWarningThenResume() {
        animateAccentColor(COLOR_WARNING);
        postDelayed(() -> {
            if (scanningActive) {
                animateAccentColor(COLOR_SCANNING);
            }
        }, 1200L);
    }

    private void animateAccentColor(int targetColor) {
        if (colorAnimator != null) {
            colorAnimator.cancel();
        }
        colorAnimator = ValueAnimator.ofObject(new ArgbEvaluator(), currentAccentColor, targetColor);
        colorAnimator.setDuration(240L);
        colorAnimator.addUpdateListener(anim -> {
            currentAccentColor = (Integer) anim.getAnimatedValue();
            cornerPaint.setColor(currentAccentColor);
            invalidate();
        });
        colorAnimator.start();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float size = Math.min(w, h) * 0.66f;
        float maxPx = 280f * getResources().getDisplayMetrics().density;
        size = Math.min(size, maxPx);
        float left = (w - size) / 2f;
        float top = (h - size) / 2f - 24f * getResources().getDisplayMetrics().density;
        frameRect.set(left, top, left + size, top + size);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0 || frameRect.isEmpty()) {
            return;
        }
        float density = getResources().getDisplayMetrics().density;

        // 4 outer scrim rectangles leaving the viewfinder crystal clear
        canvas.drawRect(0, 0, w, frameRect.top, scrimPaint);
        canvas.drawRect(0, frameRect.bottom, w, h, scrimPaint);
        canvas.drawRect(0, frameRect.top, frameRect.left, frameRect.bottom, scrimPaint);
        canvas.drawRect(frameRect.right, frameRect.top, w, frameRect.bottom, scrimPaint);

        // Subtle inner frame border
        canvas.drawRect(frameRect, borderPaint);

        // 4 crisp corner brackets
        float cornerLen = 24f * density;
        float l = frameRect.left;
        float t = frameRect.top;
        float r = frameRect.right;
        float b = frameRect.bottom;

        canvas.drawLine(l, t, l + cornerLen, t, cornerPaint);
        canvas.drawLine(l, t, l, t + cornerLen, cornerPaint);

        canvas.drawLine(r - cornerLen, t, r, t, cornerPaint);
        canvas.drawLine(r, t, r, t + cornerLen, cornerPaint);

        canvas.drawLine(l, b, l + cornerLen, b, cornerPaint);
        canvas.drawLine(l, b - cornerLen, l, b, cornerPaint);

        canvas.drawLine(r - cornerLen, b, r, b, cornerPaint);
        canvas.drawLine(r, b - cornerLen, r, b, cornerPaint);

        // Animated scanning beam while active
        if (scanningActive) {
            float y = frameRect.top + frameRect.height() * scanProgress;
            float beamH = 2.5f * density;
            float pad = 10f * density;
            laserPaint.setShader(new LinearGradient(
                    l + pad, y, r - pad, y,
                    new int[]{0x0022C55E, 0xEE22C55E, 0x0022C55E},
                    new float[]{0f, 0.5f, 1f},
                    Shader.TileMode.CLAMP));
            canvas.drawRect(l + pad, y - beamH / 2f, r - pad, y + beamH / 2f, laserPaint);
        }
    }
}
