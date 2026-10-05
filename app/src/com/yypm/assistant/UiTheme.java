package com.yypm.assistant;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.util.SparseArray;
import android.view.View;

public final class UiTheme {

    public static final String K_UI_THEME = "ui_theme";
    public static final int THEME_DEF = 0;

    public static final String[] THEME_NAME = {
            "午夜鎏金", "画廊纸感", "全息扫光", "线稿档案", "面板融合", "MELLO"
    };

    private UiTheme() {}

    public static int current(Context c) {
        return XqService.spIntOf(c, K_UI_THEME, THEME_DEF);
    }

    public static void set(Context c, int id) {
        XqService.spPutInt(K_UI_THEME, id);
    }

    public static void apply(View root, int id) {
        if (root == null) return;
        root.setBackground(new Bg(id));
    }

    public static int statusColor(int id) {
        switch (id) {
            case 1: return 0xFF14100C;
            case 3: return 0xFF0C0F14;
            case 5: return 0xFFE7DFCE;
            default: return 0xFF0B1014;
        }
    }

    private static final SparseArray<Bitmap> CACHE = new SparseArray<>();

    private static Bitmap bmp(int resId) {
        Bitmap b = CACHE.get(resId);
        if (b != null) return b;
        Context c = XqService.appCtx;
        if (c == null) return null;
        try {
            b = BitmapFactory.decodeResource(c.getResources(), resId);
            if (b != null) CACHE.put(resId, b);
        } catch (Throwable ignored) {}
        return b;
    }

    static final class Bg extends Drawable {
        final int theme;
        Bg(int theme) { this.theme = theme; }

        @Override public void draw(Canvas canvas) {
            Rect b = getBounds();
            if (b.isEmpty()) return;
            int w = b.width(), h = b.height();
            if (w <= 0 || h <= 0) return;

            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            p.setStyle(Paint.Style.FILL);
            p.setShader(bgShader(w, h));
            canvas.drawRect(b, p);
            p.setShader(null);

            switch (theme) {
                case 1: drawPaper(canvas, b, w, h); break;
                case 3: drawLineart(canvas, b, w, h); break;
                case 4: drawFusion(canvas, b, w, h); break;
                case 5: drawMello(canvas, b, w, h); break;
                default: drawStandard(canvas, b, w, h, theme); break;
            }
        }

        private LinearGradient bgShader(int w, int h) {
            int top, bot;
            switch (theme) {
                case 1: top = 0xFF3A3024; bot = 0xFF14100C; break;
                case 3: top = 0xFF171B22; bot = 0xFF0C0F14; break;
                case 2: top = 0xFF141A28; bot = 0xFF06070C; break;
                case 4: top = 0xFF161B26; bot = 0xFF070910; break;
                case 5: top = 0xFFE7DFCE; bot = 0xFFD8CCB4; break;
                default: top = 0xFF1A2030; bot = 0xFF070910; break;
            }
            return new LinearGradient(0, 0, 0, h, top, bot, Shader.TileMode.CLAMP);
        }

        private void drawStandard(Canvas c, Rect b, int w, int h, int t) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            Bitmap stars = bmp(R.drawable.ui_theme_stars);
            if (stars != null) {
                p.setAlpha(130);
                drawCover(c, stars, b, p);
                p.setAlpha(255);
            }
            Bitmap sub = bmp(R.drawable.ui_theme_subject);
            if (sub != null) {
                p.setAlpha(235);
                drawPerson(c, sub, b, w, h, 0.78f, p);
                p.setAlpha(255);
            }
            if (t == 2) drawSheen(c, b);
        }

        private void drawPaper(Canvas c, Rect b, int w, int h) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            Bitmap paper = bmp(R.drawable.ui_theme_paper);
            if (paper != null) drawCover(c, paper, b, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xCC0B0E12);
            c.drawRect(b, p);
            Bitmap stars = bmp(R.drawable.ui_theme_stars);
            if (stars != null) {
                p.setAlpha(100);
                drawCover(c, stars, b, p);
                p.setAlpha(255);
            }
            Bitmap sub = bmp(R.drawable.ui_theme_subject);
            if (sub != null) {
                p.setAlpha(235);
                drawPerson(c, sub, b, w, h, 0.78f, p);
                p.setAlpha(255);
            }
        }

        private void drawLineart(Canvas c, Rect b, int w, int h) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            Bitmap line = bmp(R.drawable.ui_theme_lineart);
            if (line != null) {
                p.setAlpha(22);
                drawCover(c, line, b, p);
                p.setAlpha(255);
            }
            p.setStyle(Paint.Style.FILL);
            int ax = b.left + dp(30);
            p.setShader(new LinearGradient(b.left, b.top, ax, b.top,
                    0xFFD4AF37, 0x00D4AF37, Shader.TileMode.CLAMP));
            int ay = b.top + (int) (h * 0.16f);
            c.drawRect(b.left, ay, ax, ay + dp(3), p);
            p.setShader(null);
            Bitmap sub = bmp(R.drawable.ui_theme_subject);
            if (sub != null) {
                p.setAlpha(235);
                drawPerson(c, sub, b, w, h, 0.45f, p);
                p.setAlpha(255);
            }
        }

        private void drawFusion(Canvas c, Rect b, int w, int h) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            Bitmap sub = bmp(R.drawable.ui_theme_subject);
            if (sub != null) {
                p.setAlpha(150);
                drawCover(c, sub, b, p);
                p.setAlpha(255);
            }
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xB30A0D13);
            c.drawRect(b, p);
            Bitmap stars = bmp(R.drawable.ui_theme_stars);
            if (stars != null) {
                p.setAlpha(70);
                drawCover(c, stars, b, p);
                p.setAlpha(255);
            }
        }

        private void drawMello(Canvas c, Rect b, int w, int h) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            Bitmap card = bmp(R.drawable.ui_theme_mello);
            if (card != null) drawCover(c, card, b, p);
        }

        private void drawSheen(Canvas c, Rect b) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.FILL);
            p.setShader(new LinearGradient(
                    b.left + b.width(), b.top,
                    b.left, b.top + b.height(),
                    new int[]{0x00000000, 0x26FFD782, 0x2A96DCFF, 0x20FF9FD2, 0x00000000},
                    new float[]{0f, 0.42f, 0.5f, 0.58f, 1f},
                    Shader.TileMode.CLAMP));
            c.drawRect(b, p);
            p.setShader(null);
        }

        private void drawCover(Canvas c, Bitmap bmp, Rect b, Paint p) {
            if (bmp == null || b.isEmpty()) return;
            float s = Math.max(b.width() / (float) bmp.getWidth(),
                    b.height() / (float) bmp.getHeight());
            int dw = (int) (bmp.getWidth() * s + 0.5f);
            int dh = (int) (bmp.getHeight() * s + 0.5f);
            int dx = b.left + (b.width() - dw) / 2;
            int dy = b.top + (b.height() - dh) / 2;
            c.drawBitmap(bmp, null, new Rect(dx, dy, dx + dw, dy + dh), p);
        }

        private void drawPerson(Canvas c, Bitmap sub, Rect b, int w, int h, float frac, Paint p) {
            float iw = w * frac;
            float ih = iw * sub.getHeight() / (float) sub.getWidth();
            float left = b.left + w - iw + w * 0.06f;
            float top = b.top + h - ih + h * 0.04f;
            c.drawBitmap(sub, null,
                    new Rect((int) left, (int) top, (int) (left + iw), (int) (top + ih)), p);
        }

        private int dp(float v) {
            android.content.Context c = XqService.appCtx;
            float d = (c != null) ? c.getResources().getDisplayMetrics().density : 3f;
            return (int) (v * d + 0.5f);
        }

        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(ColorFilter cf) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
