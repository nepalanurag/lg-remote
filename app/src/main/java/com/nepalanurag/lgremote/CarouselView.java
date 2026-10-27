package com.nepalanurag.lgremote;

import android.content.Context;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * Lightweight, zero-dependency snap-scrolling carousel for the upper control deck.
 * Sizes all child pages to match the viewport width and smoothly snaps on swipe.
 */
public class CarouselView extends HorizontalScrollView {

    public interface OnPageChangeListener {
        void onPageChanged(int page);
    }

    private final LinearLayout container;
    private final List<View> pages = new ArrayList<>();
    private int currentPage = 0;
    private OnPageChangeListener pageChangeListener;
    private boolean isUserTouching = false;

    public CarouselView(Context context) {
        super(context);
        setHorizontalScrollBarEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);

        container = new LinearLayout(context);
        container.setOrientation(LinearLayout.HORIZONTAL);
        container.setGravity(Gravity.CENTER_VERTICAL);
        addView(container, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));
    }

    public void setOnPageChangeListener(OnPageChangeListener listener) {
        this.pageChangeListener = listener;
    }

    public void addPage(View page) {
        pages.add(page);
        int w = getWidth();
        if (w <= 0) {
            w = getResources().getDisplayMetrics().widthPixels - Ui.dp(getContext(), 24);
        }
        container.addView(page, new LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0) {
            for (int i = 0; i < container.getChildCount(); i++) {
                View child = container.getChildAt(i);
                ViewGroup.LayoutParams lp = child.getLayoutParams();
                lp.width = w;
                child.setLayoutParams(lp);
            }
            post(() -> scrollTo(currentPage * w, 0));
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
            isUserTouching = true;
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            isUserTouching = false;
            post(this::snapToNearestPage);
        }
        return super.onTouchEvent(ev);
    }

    @Override
    protected void onScrollChanged(int l, int t, int oldl, int oldt) {
        super.onScrollChanged(l, t, oldl, oldt);
        int w = getWidth();
        if (w > 0 && isUserTouching) {
            int page = Math.round((float) l / w);
            page = Math.max(0, Math.min(pages.size() - 1, page));
            if (page != currentPage) {
                currentPage = page;
                if (pageChangeListener != null) {
                    pageChangeListener.onPageChanged(currentPage);
                }
            }
        }
    }

    public void snapToNearestPage() {
        int w = getWidth();
        if (w <= 0) return;
        int page = Math.round((float) getScrollX() / w);
        page = Math.max(0, Math.min(pages.size() - 1, page));
        setPage(page, true);
    }

    public void setPage(int page, boolean smooth) {
        if (page < 0 || page >= pages.size()) return;
        currentPage = page;
        int w = getWidth();
        if (w > 0) {
            if (smooth) {
                smoothScrollTo(page * w, 0);
            } else {
                scrollTo(page * w, 0);
            }
        }
        if (pageChangeListener != null) {
            pageChangeListener.onPageChanged(currentPage);
        }
    }

    public int getCurrentPage() {
        return currentPage;
    }

    public int getPageCount() {
        return pages.size();
    }
}
