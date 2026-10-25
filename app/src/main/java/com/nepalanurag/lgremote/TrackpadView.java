package com.nepalanurag.lgremote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * Relative trackpad. One finger moves the TV pointer; tap clicks; two fingers scroll;
 * two-finger tap secondary clicks.
 */
public final class TrackpadView extends View {
    public interface Listener {
        void onMove(int dx, int dy, boolean buttonDown);

        void onClick();

        void onScroll(int dx, int dy);

        default void onSecondaryClick() {}
    }

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sub = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tag = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF panel = new RectF();
    private final int touchSlop;

    private Listener listener;
    private float speed = 1.6f;
    private boolean haptics = true;
    private String tagLabel = "TRACKPAD";
    private String hintLabel;
    private String subLabel;
    private boolean touching;
    private boolean multiTouch;
    private float lastX;
    private float lastY;
    private float totalMove;
    private long downAt;
    private float accX;
    private float accY;
    private float scrollAccX;
    private float scrollAccY;
    private float scrollLastX;
    private float scrollLastY;
    private float twoFingerMove;
    private long twoFingerDownAt;
    private float dotX;
    private float dotY;

    public TrackpadView(Context context) {
        super(context);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        init();
    }

    public TrackpadView(Context context, AttributeSet attrs) {
        super(context, attrs);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        init();
    }

    private void init() {
        setClickable(true);
        setFocusable(true);
        setContentDescription("Trackpad");
        fill.setColor(getContext().getColor(R.color.surface));
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Ui.dp(getContext(), 1));
        stroke.setColor(getContext().getColor(R.color.stroke));
        hint.setColor(getContext().getColor(R.color.text));
        hint.setTextAlign(Paint.Align.CENTER);
        hint.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        hint.setTextSize(Ui.dp(getContext(), 17));
        sub.setColor(getContext().getColor(R.color.muted));
        sub.setTextAlign(Paint.Align.CENTER);
        sub.setTypeface(android.graphics.Typeface.create("sans-serif",
                android.graphics.Typeface.NORMAL));
        sub.setTextSize(Ui.dp(getContext(), 12));
        dot.setColor(getContext().getColor(R.color.accent));
        dotGlow.setColor(getContext().getColor(R.color.accent));
        dotGlow.setAlpha(35);
        tag.setColor(getContext().getColor(R.color.muted));
        tag.setTextSize(Ui.dp(getContext(), 9));
        tag.setTypeface(android.graphics.Typeface.create("sans-serif",
                android.graphics.Typeface.BOLD));
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setPointerSpeed(float speed) {
        this.speed = Math.max(0.5f, Math.min(3f, speed));
    }

    public void setHaptics(boolean enabled) {
        haptics = enabled;
    }

    public void setLabels(String tag, String hint, String sub) {
        this.tagLabel = tag != null ? tag : "TRACKPAD";
        this.hintLabel = hint;
        this.subLabel = sub;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        panel.set(Ui.dp(getContext(), 1), Ui.dp(getContext(), 1),
                w - Ui.dp(getContext(), 1), h - Ui.dp(getContext(), 1));
        dotX = w / 2f;
        dotY = h / 2f;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float radius = Ui.dp(getContext(), 24);
        canvas.drawRoundRect(panel, radius, radius, fill);
        stroke.setColor(getContext().getColor(touching ? R.color.accent_dim : R.color.stroke));
        canvas.drawRoundRect(panel, radius, radius, stroke);

        canvas.drawText(tagLabel, Ui.dp(getContext(), 18),
                Ui.dp(getContext(), 26), tag);

        if (!touching) {
            float midY = getHeight() / 2f;
            float midX = getWidth() / 2f;
            String hText = hintLabel != null ? hintLabel : getContext().getString(R.string.trackpad_hint);
            String sText = subLabel != null ? subLabel : "Tap to click · 2-finger scroll";
            canvas.drawText(hText, midX, midY - Ui.dp(getContext(), 4), hint);
            canvas.drawText(sText, midX, midY + Ui.dp(getContext(), 18), sub);
        } else {
            canvas.drawCircle(dotX, dotY, Ui.dp(getContext(), 20), dotGlow);
            canvas.drawCircle(dotX, dotY, Ui.dp(getContext(), 5), dot);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) {
            return true;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                touching = true;
                multiTouch = false;
                lastX = dotX = event.getX();
                lastY = dotY = event.getY();
                totalMove = 0;
                downAt = System.currentTimeMillis();
                accX = 0;
                accY = 0;
                invalidate();
                return true;

            case MotionEvent.ACTION_POINTER_DOWN:
                if (event.getPointerCount() >= 2) {
                    multiTouch = true;
                    scrollLastX = averageX(event);
                    scrollLastY = averageY(event);
                    scrollAccX = 0;
                    scrollAccY = 0;
                    twoFingerMove = 0;
                    twoFingerDownAt = System.currentTimeMillis();
                    invalidate();
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                if (multiTouch) {
                    if (event.getPointerCount() >= 2) {
                        float x = averageX(event);
                        float y = averageY(event);
                        float dx = x - scrollLastX;
                        float dy = y - scrollLastY;
                        scrollLastX = x;
                        scrollLastY = y;
                        twoFingerMove += Math.hypot(dx, dy);

                        // Accumulate natural scroll deltas (positive sy when swiping up)
                        scrollAccX += dx * 0.65f;
                        scrollAccY += (-dy) * 0.65f;
                        int sx = (int) scrollAccX;
                        int sy = (int) scrollAccY;
                        if (sx != 0 || sy != 0) {
                            scrollAccX -= sx;
                            scrollAccY -= sy;
                            if (listener != null) {
                                listener.onScroll(sx, sy);
                            }
                        }
                    }
                    return true;
                }

                // Single finger cursor movement
                float x = event.getX();
                float y = event.getY();
                float dx = x - lastX;
                float dy = y - lastY;
                totalMove += Math.abs(dx) + Math.abs(dy);
                lastX = x;
                lastY = y;
                dotX = x;
                dotY = y;
                if (totalMove < touchSlop) {
                    invalidate();
                    return true;
                }
                accX += dx * speed;
                accY += dy * speed;
                int mx = (int) accX;
                int my = (int) accY;
                if (mx != 0 || my != 0) {
                    accX -= mx;
                    accY -= my;
                    sendMove(mx, my, false);
                }
                invalidate();
                return true;

            case MotionEvent.ACTION_POINTER_UP:
                if (event.getPointerCount() == 2) {
                    long duration = System.currentTimeMillis() - twoFingerDownAt;
                    if (twoFingerMove < touchSlop && duration < 400) {
                        if (listener != null) {
                            listener.onSecondaryClick();
                        }
                        haptic(HapticFeedbackConstants.CONTEXT_CLICK);
                    }
                }
                // Lock cursor movement when lifting multi-touch fingers to prevent jumping
                return true;

            case MotionEvent.ACTION_UP:
                boolean tap = !multiTouch && totalMove < touchSlop
                        && System.currentTimeMillis() - downAt < 500;
                if (tap) {
                    if (listener != null) {
                        listener.onClick();
                    }
                    haptic(HapticFeedbackConstants.KEYBOARD_TAP);
                }
                ending();
                return true;

            case MotionEvent.ACTION_CANCEL:
                ending();
                return true;

            default:
                return true;
        }
    }

    private void ending() {
        touching = false;
        multiTouch = false;
        invalidate();
    }

    private void sendMove(int dx, int dy, boolean down) {
        if (listener != null) {
            listener.onMove(dx, dy, down);
        }
    }

    private void haptic(int type) {
        if (haptics) {
            performHapticFeedback(type);
        }
    }

    private static float averageX(MotionEvent event) {
        float sum = 0;
        for (int i = 0; i < event.getPointerCount(); i++) {
            sum += event.getX(i);
        }
        return sum / event.getPointerCount();
    }

    private static float averageY(MotionEvent event) {
        float sum = 0;
        for (int i = 0; i < event.getPointerCount(); i++) {
            sum += event.getY(i);
        }
        return sum / event.getPointerCount();
    }

    @Override
    public boolean performClick() {
        super.performClick();
        if (listener != null) {
            listener.onClick();
        }
        return true;
    }
}
