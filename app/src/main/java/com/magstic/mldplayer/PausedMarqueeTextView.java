package com.magstic.mldplayer;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatTextView;

public final class PausedMarqueeTextView extends AppCompatTextView {
    private static final long START_HOLD_MS = 1800L;
    private static final long END_HOLD_MS = 2600L;
    private static final float SCROLL_SPEED_DP_PER_SECOND = 16f;

    private Animator marqueeAnimator;
    private float marqueeOffsetPx;
    private final Runnable restartRunnable = new Runnable() {
        @Override
        public void run() {
            restartMarqueeInternal();
        }
    };

    public PausedMarqueeTextView(@NonNull Context context) {
        super(context);
        init();
    }

    public PausedMarqueeTextView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public PausedMarqueeTextView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setSingleLine(true);
        setMaxLines(1);
        setHorizontallyScrolling(false);
        setHorizontalFadingEdgeEnabled(false);
        setEllipsize(null);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        scheduleRestart();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE) {
            scheduleRestart();
        } else {
            cancelMarquee();
        }
    }

    @Override
    protected void onVisibilityChanged(@NonNull View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == VISIBLE) {
            scheduleRestart();
        } else {
            cancelMarquee();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelMarquee();
        removeCallbacks(restartRunnable);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onTextChanged(CharSequence text, int start, int lengthBefore, int lengthAfter) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter);
        marqueeOffsetPx = 0f;
        scheduleRestart();
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (width != oldWidth) {
            scheduleRestart();
        }
    }

    private void scheduleRestart() {
        removeCallbacks(restartRunnable);
        post(restartRunnable);
    }

    public void restartMarquee() {
        scheduleRestart();
    }

    private void restartMarqueeInternal() {
        int availableWidth;
        float textWidth;
        float maxScroll;

        cancelMarquee();
        if (!isAttachedToWindow()) {
            return;
        }
        availableWidth = getWidth() - getCompoundPaddingLeft() - getCompoundPaddingRight();
        if (availableWidth <= 0) {
            return;
        }
        textWidth = getPaint().measureText(getText() == null ? "" : getText().toString());
        maxScroll = Math.max(0, textWidth - availableWidth);
        if (maxScroll <= 0) {
            marqueeOffsetPx = 0f;
            invalidate();
            return;
        }
        marqueeAnimator = buildAnimator(maxScroll);
        marqueeAnimator.start();
    }

    private Animator buildAnimator(float maxScroll) {
        long duration = computeDuration(maxScroll);
        final boolean[] cancelled = new boolean[] { false };
        ValueAnimator holdStart = ValueAnimator.ofFloat(0f, 0f);
        ValueAnimator scrollForward = ValueAnimator.ofFloat(0f, maxScroll);
        ValueAnimator holdEnd = ValueAnimator.ofFloat(maxScroll, maxScroll);
        ValueAnimator scrollBack = ValueAnimator.ofFloat(maxScroll, 0f);
        AnimatorSet sequence = new AnimatorSet();

        holdStart.setDuration(START_HOLD_MS);
        holdEnd.setDuration(END_HOLD_MS);
        scrollForward.setDuration(duration);
        scrollBack.setDuration(duration);
        scrollForward.addUpdateListener(animation -> updateOffset((Float) animation.getAnimatedValue()));
        scrollBack.addUpdateListener(animation -> updateOffset((Float) animation.getAnimatedValue()));
        sequence.playSequentially(holdStart, scrollForward, holdEnd, scrollBack);
        sequence.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationCancel(Animator animation) {
                cancelled[0] = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (!cancelled[0] && animation == marqueeAnimator && isAttachedToWindow()) {
                    post(restartRunnable);
                }
            }
        });
        return sequence;
    }

    private long computeDuration(float distancePx) {
        float density = getResources().getDisplayMetrics().density;
        float speedPxPerSecond = SCROLL_SPEED_DP_PER_SECOND * density;

        return Math.max(1600L, (long) ((distancePx / speedPxPerSecond) * 1000f));
    }

    private void updateOffset(float offsetPx) {
        marqueeOffsetPx = offsetPx;
        invalidate();
    }

    private void cancelMarquee() {
        if (marqueeAnimator != null) {
            marqueeAnimator.cancel();
            marqueeAnimator = null;
        }
        marqueeOffsetPx = 0f;
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        int saveCount = canvas.save();
        CharSequence text = getText();
        String value = text == null ? "" : text.toString();
        float textX = getCompoundPaddingLeft() - marqueeOffsetPx;
        int baseline = getBaseline();

        getPaint().setColor(getCurrentTextColor());
        getPaint().drawableState = getDrawableState();
        canvas.clipRect(
                getCompoundPaddingLeft(),
                0,
                getWidth() - getCompoundPaddingRight(),
                getHeight());
        canvas.drawText(value, textX, baseline, getPaint());
        canvas.restoreToCount(saveCount);
    }
}
