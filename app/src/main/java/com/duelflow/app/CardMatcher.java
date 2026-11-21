package com.duelflow.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 卡图模板匹配（纯 Java，无 Android 依赖，可在桌面 JVM 里跑测试）。
 *
 * 思路：
 *  1. 把整屏灰度图缩到 540 宽的工作分辨率；
 *  2. 按几档「卡宽」滑窗，用画框区域的 8x8 **面积平均**描述子快速筛候选
 *     （面积平均而不是点采样，1~2 像素的错位不会让分数崩掉）；
 *  3. 对候选做 36x32 的归一化互相关（NCC）精匹配，得到卡名与置信度；
 *  4. 精匹配分数低于接受阈值但仍有纹理的位置，作为「未识别卡」返回，界面上提示手动补。
 *
 * 只比画框（插画）区域，所以同系列卡片的共同边框不会干扰。
 */
public final class CardMatcher {

    /* 画框在整张卡里的相对位置（按 ygoprodeck 卡图量出来） */
    public static final float ART_X0 = 0.085f;
    public static final float ART_X1 = 0.915f;
    public static final float ART_Y0 = 0.14f;
    public static final float ART_Y1 = 0.65f;
    /* 卡高 / 卡宽 */
    public static final float CARD_ASPECT = 1.457f;

    public static final int DESC = 8;
    public static final int FULL_W = 36;
    public static final int FULL_H = 32;

    private CardMatcher() {
    }

    /* ---------------- 积分图 ---------------- */

    static final class Integral {
        final int w, h;
        final double[] sat;
        final double[] sat2;

        Integral(float[] gray, int w, int h) {
            this.w = w;
            this.h = h;
            int rw = w + 1;
            sat = new double[rw * (h + 1)];
            sat2 = new double[rw * (h + 1)];
            for (int y = 0; y < h; y++) {
                double rs = 0, rs2 = 0;
                int row = (y + 1) * rw, prev = y * rw;
                for (int x = 0; x < w; x++) {
                    float v = gray[y * w + x];
                    rs += v;
                    rs2 += (double) v * v;
                    sat[row + x + 1] = sat[prev + x + 1] + rs;
                    sat2[row + x + 1] = sat2[prev + x + 1] + rs2;
                }
            }
        }

        double sum(int x0, int y0, int x1, int y1) {
            int rw = w + 1;
            return sat[y1 * rw + x1] - sat[y0 * rw + x1] - sat[y1 * rw + x0] + sat[y0 * rw + x0];
        }

        double sum2(int x0, int y0, int x1, int y1) {
            int rw = w + 1;
            return sat2[y1 * rw + x1] - sat2[y0 * rw + x1] - sat2[y1 * rw + x0] + sat2[y0 * rw + x0];
        }
    }

    /**
     * 把矩形区域按面积平均采样成 nw x nh（每格取该格覆盖像素的均值）。
     * 比逐点点采样抗错位：窗口偏 1~2 像素时格均值几乎不变。
     */
    static float[] sampleArea(Integral ig, float x0, float y0, float x1, float y1, int nw, int nh) {
        float[] out = new float[nw * nh];
        float dx = (x1 - x0) / nw, dy = (y1 - y0) / nh;
        for (int y = 0; y < nh; y++) {
            int iy0 = clampIdx(Math.round(y0 + y * dy), ig.h);
            int iy1 = clampIdx(Math.round(y0 + (y + 1) * dy), ig.h);
            if (iy1 <= iy0) iy1 = Math.min(ig.h, iy0 + 1);
            for (int x = 0; x < nw; x++) {
                int ix0 = clampIdx(Math.round(x0 + x * dx), ig.w);
                int ix1 = clampIdx(Math.round(x0 + (x + 1) * dx), ig.w);
                if (ix1 <= ix0) ix1 = Math.min(ig.w, ix0 + 1);
                int area = (ix1 - ix0) * (iy1 - iy0);
                double s = ig.sum(ix0, iy0, ix1, iy1);
                out[y * nw + x] = area > 0 ? (float) (s / area) : 0f;
            }
        }
        return out;
    }

    static int clampIdx(int v, int n) {
        return v < 0 ? 0 : (v > n ? n : v);
    }

    /* ---------------- 模板 ---------------- */

    public static final class Template {
        public final int index;
        public final String name;
        public final float[] desc;
        public final float[] full;

        Template(int index, String name, float[] desc, float[] full) {
            this.index = index;
            this.name = name;
            this.desc = desc;
            this.full = full;
        }

        /** 从一张卡图（整卡灰度）建立模板。 */
        public static Template build(int index, String name, float[] cardGray, int w, int h) {
            Integral ig = new Integral(cardGray, w, h);
            float ax0 = ART_X0 * w, ax1 = ART_X1 * w;
            float ay0 = ART_Y0 * h, ay1 = ART_Y1 * h;
            float[] desc = sampleArea(ig, ax0, ay0, ax1, ay1, DESC, DESC);
            normalize(desc);
            float[] full = sampleArea(ig, ax0, ay0, ax1, ay1, FULL_W, FULL_H);
            normalize(full);
            return new Template(index, name, desc, full);
        }
    }

    /* ---------------- 结果 ---------------- */

    public static final class Hit {
        public int index = -1;   // 模板下标；-1 表示「有卡但没认出来」
        public String name;
        public float score;
        public float cx, cy, cw, ch;   // 原图坐标

        public boolean unknown() {
            return index < 0;
        }

        @Override
        public String toString() {
            return (name == null ? "?" : name) + "@" + Math.round(cx) + "," + Math.round(cy)
                    + " s=" + String.format("%.2f", score);
        }
    }

    public static final class Options {
        /** 卡宽相对工作图宽度的比例档位（0.045 ≈ 手牌，0.21 ≈ 场上大卡） */
        public float[] widthFactors = {0.045f, 0.052f, 0.06f, 0.07f, 0.082f, 0.095f,
                0.11f, 0.13f, 0.155f, 0.185f, 0.215f};
        public int workWidth = 1080;
        /** 粗筛阈值（8x8 面积平均描述子 NCC） */
        public float descGate = 0.62f;
        /** 精匹配接受阈值 */
        public float acceptScore = 0.75f;
        /** 低于接受但高于此值 → 报「未识别卡」 */
        public float unknownScore = 0.55f;
        /** 第一名比第二名至少高这么多才算「认准」 */
        public float margin = 0.04f;
        public int maxCandidates = 220;
        /** 纹理门限：画框内灰度标准差低于此值直接跳过（空白场地） */
        public float minStd = 21f;
    }

    /* ---------------- 灰度 ---------------- */

    /** ARGB 像素 → 工作分辨率灰度（面积平均），并返回尺寸 [w,h]。 */
    public static float[] toGray(int[] argb, int w, int h, int workWidth, int[] outSize) {
        int ww = Math.min(workWidth, w);
        if (ww < 32) ww = Math.min(32, w);
        int wh = Math.max(1, Math.round(h * (float) ww / w));
        float[] dst = new float[ww * wh];
        float sx = (float) w / ww, sy = (float) h / wh;
        for (int y = 0; y < wh; y++) {
            int y0 = (int) (y * sy);
            int y1 = Math.min(h, Math.max(y0 + 1, (int) ((y + 1) * sy)));
            for (int x = 0; x < ww; x++) {
                int x0 = (int) (x * sx);
                int x1 = Math.min(w, Math.max(x0 + 1, (int) ((x + 1) * sx)));
                float sum = 0f;
                int n = 0;
                for (int yy = y0; yy < y1; yy++) {
                    int base = yy * w;
                    for (int xx = x0; xx < x1; xx++) {
                        int c = argb[base + xx];
                        sum += 0.299f * ((c >> 16) & 0xff) + 0.587f * ((c >> 8) & 0xff) + 0.114f * (c & 0xff);
                        n++;
                    }
                }
                dst[y * ww + x] = n > 0 ? sum / n : 0f;
            }
        }
        if (outSize != null && outSize.length >= 2) {
            outSize[0] = ww;
            outSize[1] = wh;
        }
        return dst;
    }

    /* ---------------- 主流程 ---------------- */

    public static List<Hit> match(int[] argb, int w, int h, List<Template> templates) {
        return match(argb, w, h, templates, new Options());
    }

    public static List<Hit> match(int[] argb, int w, int h, List<Template> templates, Options o) {
        if (argb == null || templates == null || templates.isEmpty()) return new ArrayList<>();
        int[] size = new int[2];
        float[] work = toGray(argb, w, h, o.workWidth, size);
        return matchGray(work, size[0], size[1], templates, o, (float) w / size[0]);
    }

    /** 工作分辨率灰度 → 命中列表；坐标乘 coordScale 还原到原图。 */
    public static List<Hit> matchGray(float[] work, int ww, int wh,
                                      List<Template> templates, Options o, float coordScale) {
        List<Hit> out = new ArrayList<>();
        if (work == null || ww <= 0 || wh <= 0 || templates.isEmpty()) return out;

        Integral ig = new Integral(work, ww, wh);
        int nt = templates.size();
        List<float[]> cands = scanCandidates(ig, ww, wh, templates, o);
        if (cands == null) return out;
        return matchCandidates(ig, ww, wh, templates, o, coordScale, cands);
    }

    /**
     * 第一步：滑窗 + 8x8 面积平均描述子粗筛，返回 {descScore, cx, cy, cardW, templateIdx}。
     * 抽成独立方法便于桌面测试直接检查粗筛是否漏掉真正的卡位。
     */
    static List<float[]> scanCandidates(Integral ig, int ww, int wh,
                                        List<Template> templates, Options o) {
        int nt = templates.size();
        List<float[]> cands = new ArrayList<>();

        for (float f : o.widthFactors) {
            float cw = ww * f;
            float ch = cw * CARD_ASPECT;
            if (cw < 14 || ch >= wh) continue;
            int stride = Math.max(2, Math.round(cw / 12f));
            int halfW = Math.round(cw / 2f), halfH = Math.round(ch / 2f);
            for (int cy = halfH; cy < wh - halfH; cy += stride) {
                for (int cx = halfW; cx < ww - halfW; cx += stride) {
                    int ax0 = Math.round(cx - cw / 2f + ART_X0 * cw);
                    int ax1 = Math.round(cx - cw / 2f + ART_X1 * cw);
                    int ay0 = Math.round(cy - ch / 2f + ART_Y0 * ch);
                    int ay1 = Math.round(cy - ch / 2f + ART_Y1 * ch);
                    if (ax0 < 0 || ay0 < 0 || ax1 > ww || ay1 > wh || ax1 - ax0 < 6 || ay1 - ay0 < 6) continue;
                    int area = (ax1 - ax0) * (ay1 - ay0);
                    double s = ig.sum(ax0, ay0, ax1, ay1);
                    double s2 = ig.sum2(ax0, ay0, ax1, ay1);
                    double mean = s / area;
                    double var = s2 / area - mean * mean;
                    if (var <= 0) continue;
                    if (Math.sqrt(var) < o.minStd) continue;

                    float[] d = sampleArea(ig, ax0, ay0, ax1, ay1, DESC, DESC);
                    normalize(d);
                    float bestDesc = -1f;
                    int bestIdx = -1;
                    for (int i = 0; i < nt; i++) {
                        float v = dot(d, templates.get(i).desc);
                        if (v > bestDesc) {
                            bestDesc = v;
                            bestIdx = i;
                        }
                    }
                    if (bestDesc < o.descGate) continue;
                    cands.add(new float[]{bestDesc, cx, cy, cw, bestIdx});
                }
            }
        }

        Collections.sort(cands, new Comparator<float[]>() {
            @Override
            public int compare(float[] a, float[] b) {
                return Float.compare(b[0], a[0]);
            }
        });
        return cands;
    }

    /** 第二步：候选去重 + 36x32 精匹配。 */
    static List<Hit> matchCandidates(Integral ig, int ww, int wh,
                                     List<Template> templates, Options o,
                                     float coordScale, List<float[]> cands) {
        List<Hit> out = new ArrayList<>();
        if (cands == null || cands.isEmpty()) return out;
        int nt = templates.size();

        List<float[]> kept = new ArrayList<>();
        for (float[] c : cands) {
            boolean dup = false;
            for (int i = 0; i < kept.size(); i++) {
                float[] k = kept.get(i);
                float dx = c[1] - k[1], dy = c[2] - k[2];
                float r = Math.min(c[3], k[3]) * 0.5f;
                if (dx * dx + dy * dy < r * r) {
                    dup = true;
                    break;
                }
            }
            if (!dup) kept.add(c);
            if (kept.size() >= o.maxCandidates) break;
        }

        List<Hit> hits = new ArrayList<>();
        for (float[] c : kept) {
            /* 局部对齐：粗筛网格是跳着走的，这里把中心与尺度微调到最佳位置再精确比对。
               只用粗筛认为是这张卡的模板做参考，开销很小。 */
            int tidx = (int) c[4];
            float[] ref = templates.get(tidx).desc;
            int stride = Math.max(2, Math.round(c[3] / 12f));
            int range = stride + 2;
            float bestAlign = -2f;
            float bx = c[1], by = c[2], bw = c[3];
            float[] scaleFactors = {0.90f, 0.95f, 1.0f, 1.05f, 1.11f};
            for (float sf : scaleFactors) {
                float cw = c[3] * sf;
                float ch = cw * CARD_ASPECT;
                for (int dy = -range; dy <= range; dy += 2) {
                    for (int dx = -range; dx <= range; dx += 2) {
                        float px = c[1] + dx, py = c[2] + dy;
                        float ax0 = px - cw / 2f + ART_X0 * cw;
                        float ay0 = py - ch / 2f + ART_Y0 * ch;
                        float ax1 = px - cw / 2f + ART_X1 * cw;
                        float ay1 = py - ch / 2f + ART_Y1 * ch;
                        if (ax0 < 0 || ay0 < 0 || ax1 > ww || ay1 > wh) continue;
                        float[] d = sampleArea(ig, ax0, ay0, ax1, ay1, DESC, DESC);
                        normalize(d);
                        float v = dot(d, ref);
                        if (v > bestAlign) {
                            bestAlign = v;
                            bx = px;
                            by = py;
                            bw = cw;
                        }
                    }
                }
            }

            float cw = bw, ch = cw * CARD_ASPECT;
            float ax0 = bx - cw / 2f + ART_X0 * cw;
            float ay0 = by - ch / 2f + ART_Y0 * ch;
            float ax1 = bx - cw / 2f + ART_X1 * cw;
            float ay1 = by - ch / 2f + ART_Y1 * ch;
            if (ax0 < 0 || ay0 < 0 || ax1 > ww || ay1 > wh) continue;
            float[] win = sampleArea(ig, ax0, ay0, ax1, ay1, FULL_W, FULL_H);
            normalize(win);
            float best = -1f, second = -1f;
            int bestIdx = -1;
            for (int i = 0; i < nt; i++) {
                float v = dot(win, templates.get(i).full);
                if (v > best) {
                    second = best;
                    best = v;
                    bestIdx = i;
                } else if (v > second) {
                    second = v;
                }
            }
            if (best < o.unknownScore) continue;
            /* 像不像「认准了」：第一名要明显领先第二名 */
            boolean confident = best >= o.acceptScore
                    && (second < 0 || best - second >= o.margin);
            Hit hit = new Hit();
            hit.score = best;
            hit.cx = bx * coordScale;
            hit.cy = by * coordScale;
            hit.cw = cw * coordScale;
            hit.ch = ch * coordScale;
            if (confident) {
                hit.index = bestIdx;
                hit.name = templates.get(bestIdx).name;
            }
            hits.add(hit);
        }

        Collections.sort(hits, new Comparator<Hit>() {
            @Override
            public int compare(Hit a, Hit b) {
                return Float.compare(b.score, a.score);
            }
        });
        /* 二次去重：不同尺度可能重复命中同一张卡 */
        List<Hit> uniq = new ArrayList<>();
        for (Hit hit : hits) {
            boolean dup = false;
            for (Hit k : uniq) {
                float dx = hit.cx - k.cx, dy = hit.cy - k.cy;
                float r = Math.min(hit.cw, k.cw) * 0.45f;
                if (dx * dx + dy * dy < r * r) {
                    dup = true;
                    break;
                }
            }
            if (!dup) uniq.add(hit);
        }
        out.addAll(uniq);
        return out;
    }

    /* ---------------- 基础运算 ---------------- */

    static void normalize(float[] v) {
        int n = v.length;
        double sum = 0;
        for (float x : v) sum += x;
        double mean = sum / n;
        double acc = 0;
        for (int i = 0; i < n; i++) {
            v[i] = (float) (v[i] - mean);
            acc += (double) v[i] * v[i];
        }
        double norm = Math.sqrt(acc);
        if (norm < 1e-6) {
            for (int i = 0; i < n; i++) v[i] = 0f;
            return;
        }
        float inv = (float) (1.0 / norm);
        for (int i = 0; i < n; i++) v[i] *= inv;
    }

    static float dot(float[] a, float[] b) {
        float s = 0;
        for (int i = 0; i < a.length; i++) s += a[i] * b[i];
        return s;
    }
}
