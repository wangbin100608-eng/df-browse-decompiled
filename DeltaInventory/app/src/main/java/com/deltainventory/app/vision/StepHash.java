package com.deltainventory.app.vision;

import android.graphics.Bitmap;

/** 与特征库生成端逐位一致的步长采样 dHash / 直方图（纯 Java，无 native 依赖）。 */
public final class StepHash {
    private StepHash() {}

    /** 9x8 灰度 dHash，步长采样，灰度=(R*77+G*150+B*29)>>8 */
    public static long phash(Bitmap bmp, int x, int y, int w, int h) {
        int[] px = new int[w * h];
        bmp.getPixels(px, 0, w, x, y, w, h);
        int[] gray = new int[72];
        float sx = w / 9f, sy = h / 8f;
        for (int dy = 0; dy < 8; dy++) {
            int sy0 = Math.min((int)(dy * sy), h - 1);
            for (int dx = 0; dx < 9; dx++) {
                int sx0 = Math.min((int)(dx * sx), w - 1);
                int c = px[sy0 * w + sx0];
                int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                gray[dy * 9 + dx] = (r * 77 + g * 150 + b * 29) >> 8;
            }
        }
        long hash = 0L;
        for (int yy = 0; yy < 8; yy++)
            for (int xx = 0; xx < 8; xx++)
                hash = (hash << 1) | (gray[yy * 9 + xx] > gray[yy * 9 + xx + 1] ? 1L : 0L);
        return hash;
    }

    /** 32x32 步长采样亮度归一化直方图，与特征库一致 */
    public static float[] hist(Bitmap bmp, int x, int y, int w, int h) {
        int[] px = new int[w * h];
        bmp.getPixels(px, 0, w, x, y, w, h);
        final int n = 32;
        float sx = w / (float)n, sy = h / (float)n;
        int[] rs = new int[n * n], gs = new int[n * n];
        for (int dy = 0; dy < n; dy++) {
            int sy0 = Math.min((int)(dy * sy), h - 1);
            for (int dx = 0; dx < n; dx++) {
                int sx0 = Math.min((int)(dx * sx), w - 1);
                int c = px[sy0 * w + sx0];
                rs[dy * n + dx] = (c >> 16) & 0xFF;
                gs[dy * n + dx] = (c >> 8) & 0xFF;
            }
        }
        long sr = 0, sg = 0;
        for (int v : rs) sr += v;
        for (int v : gs) sg += v;
        float avgR = sr / (float)(n * n), avgG = sg / (float)(n * n);
        float[] hist = new float[16];
        for (int i = 0; i < rs.length; i++) {
            int ri = (int)((rs[i] - avgR + 256) / 64); if (ri < 0) ri = 0; if (ri > 3) ri = 3;
            int gi = (int)((gs[i] - avgG + 256) / 64); if (gi < 0) gi = 0; if (gi > 3) gi = 3;
            hist[ri * 4 + gi] += 1f / (n * n);
        }
        return hist;
    }
}
