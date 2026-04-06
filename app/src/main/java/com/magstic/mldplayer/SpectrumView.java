package com.magstic.mldplayer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;

public final class SpectrumView extends View {
    private static final int BAR_COUNT = 20;
    private static final float MIN_BAR_RATIO = 0.12f;

    private final float[] displayedLevels = new float[BAR_COUNT];
    private final float[] staticLevels = new float[BAR_COUNT];
    private final RectF barRect = new RectF();
    private final Paint activePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint inactivePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private boolean dynamicActive;

    public SpectrumView(@NonNull Context context) {
        super(context);
        init();
    }

    public SpectrumView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public SpectrumView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        int index;

        for (index = 0; index < BAR_COUNT; index++) {
            staticLevels[index] = buildStaticLevel(index);
            displayedLevels[index] = staticLevels[index];
        }
        setPalette(0xFF5C6BC0, 0xFFCFD8DC, 0xFF90A4AE);
        showStaticBars();
    }

    public void setPalette(int activeColor, int inactiveColor, int outlineColor) {
        activePaint.setStyle(Paint.Style.FILL);
        activePaint.setColor(activeColor);
        inactivePaint.setStyle(Paint.Style.FILL);
        inactivePaint.setColor(ColorUtils.blendARGB(inactiveColor, outlineColor, 0.35f));
        invalidate();
    }

    public void showStaticBars() {
        int index;

        dynamicActive = false;
        for (index = 0; index < BAR_COUNT; index++) {
            displayedLevels[index] = staticLevels[index];
        }
        invalidate();
    }

    public void updateLevels(@Nullable float[] levels) {
        int index;

        if (levels == null || levels.length == 0) {
            showStaticBars();
            return;
        }

        dynamicActive = true;
        for (index = 0; index < BAR_COUNT; index++) {
            float nextLevel = index < levels.length ? levels[index] : MIN_BAR_RATIO;
            float currentLevel = displayedLevels[index];

            if (nextLevel > currentLevel) {
                displayedLevels[index] = (currentLevel * 0.38f) + (nextLevel * 0.62f);
            } else {
                displayedLevels[index] = (currentLevel * 0.84f) + (nextLevel * 0.16f);
            }
        }
        invalidate();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        float contentWidth = getWidth() - getPaddingLeft() - getPaddingRight();
        float contentHeight = getHeight() - getPaddingTop() - getPaddingBottom();
        float gap = dp(6f);
        float totalGap = gap * (BAR_COUNT - 1);
        float barWidth;
        float radius;
        Paint paint = dynamicActive ? activePaint : inactivePaint;
        int index;

        super.onDraw(canvas);
        if (contentWidth <= 0f || contentHeight <= 0f) {
            return;
        }
        barWidth = (contentWidth - totalGap) / BAR_COUNT;
        if (barWidth <= 0f) {
            return;
        }
        radius = Math.min(barWidth * 0.5f, dp(10f));
        for (index = 0; index < BAR_COUNT; index++) {
            float clampedLevel = Math.max(MIN_BAR_RATIO, Math.min(1f, displayedLevels[index]));
            float left = getPaddingLeft() + (index * (barWidth + gap));
            float right = left + barWidth;
            float barHeight = Math.max(dp(10f), contentHeight * clampedLevel);
            float top = getHeight() - getPaddingBottom() - barHeight;
            float bottom = getHeight() - getPaddingBottom();

            barRect.set(left, top, right, bottom);
            canvas.drawRoundRect(barRect, radius, radius, paint);
        }
    }

    private float buildStaticLevel(int index) {
        return 0.22f + (((float) Math.sin((index * 0.55f) + 0.45f) + 1f) * 0.18f);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
