package com.nepalanurag.lgremote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/** The circular webOS-style direction pad: four sectors, a large OK button, press highlighting. */
public final class DpadView extends View {
    public interface Listener {
        void onButton(String name);

        default void onButtonLongPress(String name) {}
    }

    private static final int NONE = 0;
    private static final int UP = 1;
    private static final int RIGHT = 2;
    private static final int DOWN = 3;
    private static final int LEFT = 4;
    private static final int OK = 5;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint accent = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint accentSoft = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arrow = new Path();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final RectF arcRect = new RectF();

    private Listener listener;
    private int pressed = NONE;
    private float cx;
    private float cy;
    private float radius;
    private float inner;
    private boolean haptics = true;
    private Runnable repeat;
    private boolean okLongPressed;
    private final Runnable okLongPressRunnable = () -> {
        if (pressed == OK && isEnabled()) {
            okLongPressed = true;
            if (haptics) {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            }
            if (listener != null) {
                listener.onButtonLongPress("ENTER");
            }
        }
    };

    public DpadView(Context context) {
        super(context);
        init();
    }

    public DpadView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setClickable(true);
        setFocusable(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setContentDescription("Direction pad");
        fill.setColor(getContext().getColor(R.color.surface));
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Ui.dp(getContext(), 1));
        stroke.setColor(getContext().getColor(R.color.stroke));
        accent.setColor(getContext().getColor(R.color.accent));
        accentSoft.setColor(getContext().getColor(R.color.accent_dim));
        ink.setColor(getContext().getColor(R.color.muted));
        ink.setStyle(Paint.Style.FILL);
        text.setColor(getContext().getColor(R.color.on_accent));
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD));
        text.setTextSize(Ui.dp(getContext(), 15));
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setHaptics(boolean enabled) {
        haptics = enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        if (!enabled) {
            cancelPress();
        }
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            width = Ui.dp(getContext(), 260);
        }
        int height = MeasureSpec.getSize(heightMeasureSpec);
        if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED
                || MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.AT_MOST) {
            height = width;
        }
        setMeasuredDimension(width, Math.min(width, height));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        cx = w / 2f;
        cy = h / 2f;
        radius = Math.min(w, h) / 2f - Ui.dp(getContext(), 2);
        inner = radius * 0.37f;
        arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawCircle(cx, cy, radius, fill);

        if (pressed != NONE && pressed != OK) {
            float start;
            switch (pressed) {
                case UP:
                    start = 225;
                    break;
                case RIGHT:
                    start = 315;
                    break;
                case DOWN:
                    start = 45;
                    break;
                default:
                    start = 135;
                    break;
            }
            canvas.drawArc(arcRect, start, 90, true, accentSoft);
        }

        canvas.drawCircle(cx, cy, radius, stroke);

        boolean active = pressed == OK;
        accent.setAlpha(isEnabled() ? 255 : 90);
        ink.setAlpha(isEnabled() ? 255 : 90);
        canvas.drawCircle(cx, cy, inner, accent);
        if (active) {
            Paint highlight = new Paint(Paint.ANTI_ALIAS_FLAG);
            highlight.setColor(0x44FFFFFF);
            canvas.drawCircle(cx, cy, inner, highlight);
        }

        // Arrows
        int arrowColor = pressed != NONE && pressed != OK
                ? getContext().getColor(R.color.accent) : ink.getColor();
        Paint arrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        arrowPaint.setColor(arrowColor);
        arrowPaint.setStyle(Paint.Style.FILL);
        float distance = radius * 0.66f;
        float size = Math.max(Ui.dp(getContext(), 6), radius * 0.09f);
        drawArrow(canvas, arrowPaint, cx, cy - distance, 0, size);
        drawArrow(canvas, arrowPaint, cx + distance, cy, 90, size);
        drawArrow(canvas, arrowPaint, cx, cy + distance, 180, size);
        drawArrow(canvas, arrowPaint, cx - distance, cy, 270, size);

        text.setColor(getContext().getColor(R.color.on_accent));
        text.setAlpha(isEnabled() ? 255 : 90);
        canvas.drawText("OK", cx, cy - (text.ascent() + text.descent()) / 2f, text);
    }

    private void drawArrow(Canvas canvas, Paint paint, float x, float y, float rotation, float size) {
        canvas.save();
        canvas.rotate(rotation, x, y);
        arrow.reset();
        arrow.moveTo(x, y - size);
        arrow.lineTo(x + size * 0.9f, y + size * 0.7f);
        arrow.lineTo(x - size * 0.9f, y + size * 0.7f);
        arrow.close();
        canvas.drawPath(arrow, paint);
        canvas.restore();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) {
            return true;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                press(regionAt(event.getX(), event.getY()), true);
                return true;
            case MotionEvent.ACTION_MOVE:
                int region = regionAt(event.getX(), event.getY());
                if (region != pressed) {
                    press(region, region != OK);
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (pressed == OK && !okLongPressed) {
                    dispatch(OK);
                }
                cancelPress();
                performClick();
                return true;
            default:
                return true;
        }
    }

    private void press(int region, boolean repeatAllowed) {
        if (region == NONE || region == pressed) {
            return;
        }
        pressed = region;
        handler.removeCallbacksAndMessages(null);
        okLongPressed = false;

        if (haptics) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        }

        if (region == OK) {
            // Wait 450ms to distinguish a tap click from a press-and-hold context action
            handler.postDelayed(okLongPressRunnable, 450);
        } else {
            dispatch(region);
            if (repeatAllowed && (region == UP || region == DOWN || region == LEFT || region == RIGHT)) {
                scheduleRepeat(region);
            }
        }
        invalidate();
    }

    private void scheduleRepeat(int region) {
        repeat = () -> {
            if (pressed == region && isEnabled()) {
                dispatch(region);
                if (haptics) {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                }
                handler.postDelayed(repeat, 120);
            }
        };
        handler.postDelayed(repeat, 400);
    }

    private void dispatch(int region) {
        if (listener == null) {
            return;
        }
        switch (region) {
            case UP:
                listener.onButton("UP");
                break;
            case RIGHT:
                listener.onButton("RIGHT");
                break;
            case DOWN:
                listener.onButton("DOWN");
                break;
            case LEFT:
                listener.onButton("LEFT");
                break;
            case OK:
                listener.onButton("ENTER");
                break;
            default:
                break;
        }
    }

    private void cancelPress() {
        handler.removeCallbacksAndMessages(null);
        repeat = null;
        okLongPressed = false;
        pressed = NONE;
        invalidate();
    }

    private int regionAt(float x, float y) {
        float dx = x - cx;
        float dy = y - cy;
        float distance = (float) Math.sqrt(dx * dx + dy * dy);
        if (distance > radius) {
            return NONE;
        }
        if (distance <= inner) {
            return OK;
        }
        if (Math.abs(dx) > Math.abs(dy)) {
            return dx > 0 ? RIGHT : LEFT;
        }
        return dy > 0 ? DOWN : UP;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }
}
