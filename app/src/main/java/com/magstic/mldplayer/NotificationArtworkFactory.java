package com.magstic.mldplayer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import androidx.core.content.ContextCompat;

final class NotificationArtworkFactory {
    private static final int ARTWORK_SIZE = 512;
    private static final int[] START_COLORS = new int[] {
            Color.parseColor("#4F6BFF"),
            Color.parseColor("#00A6A6"),
            Color.parseColor("#FF7A59"),
            Color.parseColor("#3E8BFF"),
            Color.parseColor("#6A5CFF")
    };
    private static final int[] END_COLORS = new int[] {
            Color.parseColor("#9AA8FF"),
            Color.parseColor("#7AE4D7"),
            Color.parseColor("#FFC46A"),
            Color.parseColor("#7FE0FF"),
            Color.parseColor("#C0A2FF")
    };

    private NotificationArtworkFactory() {
    }

    static Bitmap create(Context context, String title, String subtitle) {
        Bitmap bitmap = Bitmap.createBitmap(ARTWORK_SIZE, ARTWORK_SIZE, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        int paletteIndex = paletteIndex(title, subtitle);
        int startColor = START_COLORS[paletteIndex];
        int endColor = END_COLORS[paletteIndex];
        float size = ARTWORK_SIZE;
        Drawable noteDrawable;

        paint.setShader(new LinearGradient(
                0f,
                0f,
                size,
                size,
                startColor,
                endColor,
                Shader.TileMode.CLAMP));
        canvas.drawRect(0f, 0f, size, size, paint);

        paint.setShader(null);
        paint.setColor(withAlpha(Color.WHITE, 56));
        canvas.drawCircle(size * 0.78f, size * 0.22f, size * 0.28f, paint);
        paint.setColor(withAlpha(Color.WHITE, 32));
        canvas.drawRoundRect(new RectF(size * 0.08f, size * 0.58f, size * 0.92f, size * 0.90f), size * 0.12f, size * 0.12f, paint);
        paint.setColor(withAlpha(Color.BLACK, 18));
        canvas.drawCircle(size * 0.16f, size * 0.16f, size * 0.24f, paint);

        noteDrawable = ContextCompat.getDrawable(context, R.drawable.ic_note);
        if (noteDrawable != null) {
            int iconSize = (int) (size * 0.34f);
            int left = (ARTWORK_SIZE - iconSize) / 2;
            int top = (ARTWORK_SIZE - iconSize) / 2 - (int) (size * 0.03f);

            noteDrawable.setTint(Color.WHITE);
            noteDrawable.setBounds(new Rect(left, top, left + iconSize, top + iconSize));
            noteDrawable.draw(canvas);
        }
        return bitmap;
    }

    static int accentColor(String title, String subtitle) {
        return START_COLORS[paletteIndex(title, subtitle)];
    }

    private static int paletteIndex(String title, String subtitle) {
        int hash = 17;

        hash = 31 * hash + safe(title).hashCode();
        hash = 31 * hash + safe(subtitle).hashCode();
        return Math.abs(hash) % START_COLORS.length;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }
}
