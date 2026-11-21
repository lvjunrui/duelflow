package com.duelflow.app;

import java.util.ArrayList;
import java.util.List;

/**
 * 按 MD 的固定版面认手牌。
 *
 * 为什么不用全屏搜索：MD 的手牌永远在屏幕底部居中排成一行，全屏搜会把火焰/石纹
 * 当卡（一次 81 个假命中，分数 0.55~0.70），真正的手牌反而淹没在噪声里。
 *
 * 真机帧实测（2712×1220）：卡宽 164px = 0.0605W，卡间距 151px = 0.0557W，
 * 卡顶边 1043px = 0.855H；5 张时最左卡 x=971，与「以屏幕中心对称」吻合。
 * 卡图区仍是 x 0.085~0.915、y 0.14~0.65（与官方卡图一致）。
 *
 * 每个卡位再在小范围内做对齐搜索（手牌成扇面，外侧卡会有一点位移/缩放）。
 */
public final class SlotRecognizer {

    public static final float CARD_ASPECT = 1.457f;
    public static final float ART_X0 = 0.085f, ART_X1 = 0.915f, ART_Y0 = 0.14f, ART_Y1 = 0.65f;
    public static final int DESC_W = 18, DESC_H = 16;

    /** 手牌版面比例（实测）。 */
    public static final float HAND_CARD_W = 0.0605f;
    public static final float HAND_PITCH = 0.0557f;
    public static final float HAND_TOP = 0.855f;

    /** 认卡门槛：分数够高、且明显高于第二名（真卡 0.72~0.96，第二名通常 <0.6）。 */
    public static final float ACCEPT = 0.70f;
    public static final float MARGIN = 0.10f;

    public static final int MAX_HAND = 6;

    /** 场上格子（真机帧实测：列中心 0.3355/0.4185/0.5015/0.5844/0.6674×屏宽，
     *  行中心：对方魔陷 0.28、对方怪兽 0.456、我方怪兽 0.645、我方魔陷 0.82×屏高；
     *  场上卡约 0.0531×屏宽）。 */
    public static final float[] FIELD_COLS = {0.3355f, 0.4185f, 0.5015f, 0.5844f, 0.6674f};
    public static final float FIELD_CARD_W = 0.0531f;
    public static final float ROW_OPP_ST = 0.28f, ROW_OPP_MON = 0.456f;
    public static final float ROW_MY_MON = 0.645f, ROW_MY_ST = 0.857f;
    /** 额外怪兽区：中间那一排的两格；卡图朝我的算我的（用 0°/180° 比对判断）。 */
    public static final float ROW_EMZ = 0.495f;
    public static final float[] EMZ_COLS = {0.4185f, 0.5844f};

    public static final class Slot {
        public String name;        // null = 没认出来
        public String side;        // "opp" / "mine" / "hand"
        public float score, margin;
        public int x, y, w, h;     // 原图坐标（左上角 + 尺寸）

        public boolean confident() {
            return name != null;
        }

        @Override
        public String toString() {
            return (name == null ? "?" : name) + "@" + x + "," + y + " s=" + String.format("%.2f", score);
        }
    }

    /** 认手牌。返回最可能的张数对应的卡位列表（每项含名字或 null）。 */
    public static List<Slot> hand(int[] px, int W, int H, List<CardMatcher.Template> tpl) {
        List<Slot> empty = new ArrayList<>();
        if (tpl == null || tpl.isEmpty() || W < 200 || H < 200) return empty;

        /* 只取屏幕底部一条带子：手牌都在这里，避免整帧灰度化的开销 */
        int bandTop = (int) (0.79f * H);
        int bandH = H - bandTop;
        if (bandH < 40) return empty;
        int[] band = new int[W * bandH];
        for (int y = 0; y < bandH; y++) {
            System.arraycopy(px, (bandTop + y) * W, band, y * W, W);
        }
        int[] size = new int[2];
        float[] gray = CardMatcher.toGray(band, W, bandH, W, size);
        band = null;

        float[][] desc = smallDescs(tpl);
        List<Slot> best = empty;
        double bestQ = -1;
        for (int n = 1; n <= MAX_HAND; n++) {
            List<Slot> slots = evaluate(gray, size[0], size[1], W, H, bandTop, desc, tpl, n);
            int conf = 0;
            double sum = 0;
            for (Slot s : slots) {
                sum += Math.max(0f, s.score);
                if (s.confident()) conf++;
            }
            double q = conf * 10.0 + sum / slots.size();
            if (q > bestQ) {
                bestQ = q;
                best = slots;
            }
        }
        return best;
    }

    /** 认场上 20 个格子（双方各 5 怪兽 + 5 魔陷）。只返回认出来的。 */
    public static List<Slot> field(int[] px, int W, int H, List<CardMatcher.Template> tpl) {
        List<Slot> out = new ArrayList<>();
        if (tpl == null || tpl.isEmpty() || W < 200 || H < 200) return out;

        int bandTop = (int) (0.20f * H);
        int bandBot = (int) (0.92f * H);
        int bandH = bandBot - bandTop;
        if (bandH < 100) return out;
        int[] band = new int[W * bandH];
        for (int y = 0; y < bandH; y++) {
            System.arraycopy(px, (bandTop + y) * W, band, y * W, W);
        }
        int[] size = new int[2];
        float[] gray = CardMatcher.toGray(band, W, bandH, W, size);
        band = null;

        float[][] desc = smallDescs(tpl);
        float cardW = FIELD_CARD_W * W;
        float cardH = cardW * CARD_ASPECT;
        float[][] rows = {
                {ROW_OPP_ST, 0}, {ROW_OPP_MON, 0}, {ROW_MY_MON, 1}, {ROW_MY_ST, 1}
        };
        for (float[] row : rows) {
            float cy = row[0] * H - bandTop;
            for (int i = 0; i < FIELD_COLS.length; i++) {
                float cx = FIELD_COLS[i] * W;
                Slot s = scoreSlot(gray, size[0], size[1], desc, tpl,
                        cx - cardW / 2f, cy - cardH / 2f, cardW, cardH, W, bandTop, 32, false);
                s.side = (int) row[1] == 0 ? "opp" : "mine";
                if (s.confident()) out.add(s);
            }
        }

        /* 额外怪兽区：两格归双方共用，卡图朝我的算我的 */
        float emzCy = ROW_EMZ * H - bandTop;
        for (float col : EMZ_COLS) {
            float cx = col * W;
            Slot up = scoreSlot(gray, size[0], size[1], desc, tpl,
                    cx - cardW / 2f, emzCy - cardH / 2f, cardW, cardH, W, bandTop, 32, false);
            Slot down = scoreSlot(gray, size[0], size[1], desc, tpl,
                    cx - cardW / 2f, emzCy - cardH / 2f, cardW, cardH, W, bandTop, 32, true);
            Slot pick = up;
            String side = "mine";
            if (down.score > up.score) {
                pick = down;
                side = "opp";
            }
            if (pick.confident()) {
                pick.side = side;
                out.add(pick);
            }
        }
        return out;
    }

    private static List<Slot> evaluate(float[] gray, int gw, int gh, int W, int H, int bandTop,
                                       float[][] desc, List<CardMatcher.Template> tpl, int n) {
        float cardW = HAND_CARD_W * W;
        float pitch = HAND_PITCH * W;
        float cardH = cardW * CARD_ASPECT;
        /* 张数多时 MD 会把扇面压缩，按比例收一点间距 */
        if (n > 5) {
            float fanMax = 0.40f * W;
            float p = (fanMax - cardW) / (n - 1);
            if (p > 0 && p < pitch) pitch = p;
        }
        float top = HAND_TOP * H - bandTop;
        float fanW = cardW + (n - 1) * pitch;
        float firstX = (float) (W / 2.0 - fanW / 2.0);
        List<Slot> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Slot s = scoreSlot(gray, gw, gh, desc, tpl, firstX + i * pitch, top, cardW, cardH, W, bandTop, 16, false);
            s.side = "hand";
            out.add(s);
        }
        return out;
    }

    private static Slot scoreSlot(float[] gray, int gw, int gh, float[][] desc,
                                  List<CardMatcher.Template> tpl, float x, float top,
                                  float cardW, float cardH, int W, int bandTop, int radius,
                                  boolean flip) {
        float[] win = new float[DESC_W * DESC_H];
        float[] best = new float[]{-2, -2, -1, x, top, cardW};

        /* 粗搜：±16px、3 档缩放 */
        for (int dx = -radius; dx <= radius; dx += radius / 2) {
            for (int dy = -radius; dy <= radius; dy += radius / 2) {
                for (float sc = 0.97f; sc <= 1.031f; sc += 0.03f) {
                    tryBox(gray, gw, gh, desc, x + dx, top + dy, cardW * sc, cardH * sc, win, best, flip);
                }
            }
        }
        /* 细搜：在粗搜最优点附近再对齐一次 */
        if (best[0] > 0.35f) {
            float bx = best[3], by = best[4], bw = best[5];
            for (int dx = -(radius / 2); dx <= radius / 2; dx += 3) {
                for (int dy = -(radius / 2); dy <= radius / 2; dy += 3) {
                    for (float sc = 0.985f; sc <= 1.016f; sc += 0.015f) {
                        tryBox(gray, gw, gh, desc, bx + dx, by + dy, bw * sc, bw * sc * CARD_ASPECT, win, best, flip);
                    }
                }
            }
        }

        Slot s = new Slot();
        s.score = best[0];
        s.margin = best[0] - best[1];
        s.x = Math.round(best[3]);
        s.y = Math.round(best[4] + bandTop);
        s.w = Math.round(best[5]);
        s.h = Math.round(best[5] * CARD_ASPECT);
        if (best[2] >= 0 && s.score >= ACCEPT && s.margin >= MARGIN) {
            s.name = tpl.get((int) best[2]).name;
        }
        return s;
    }

    /** 算一个候选框的前两名分数，写回 best = {top1, top2, idx, x, top, cardW}。 */
    private static void tryBox(float[] gray, int gw, int gh, float[][] desc,
                               float cx0, float cy0, float cardW, float cardH,
                               float[] win, float[] best, boolean flip) {
        float x0 = cx0 + ART_X0 * cardW, x1 = cx0 + ART_X1 * cardW;
        float y0 = cy0 + ART_Y0 * cardH, y1 = cy0 + ART_Y1 * cardH;
        if (x0 < 0 || y0 < 0 || x1 > gw - 1 || y1 > gh - 1) return;
        sampleGray(gray, gw, gh, x0, y0, x1, y1, win);
        if (flip) reverse180(win);
        normalize(win);
        float s1 = -2, s2 = -2;
        int i1 = -1;
        for (int i = 0; i < desc.length; i++) {
            float d = dot(win, desc[i]);
            if (d > s1) {
                s2 = s1;
                s1 = d;
                i1 = i;
            } else if (d > s2) {
                s2 = d;
            }
        }
        if (s1 > best[0]) {
            best[0] = s1;
            best[1] = s2;
            best[2] = i1;
            best[3] = cx0;
            best[4] = cy0;
            best[5] = cardW;
        }
    }

    /** Template.full 是 36x32，池化成 18x16（低通后抗模糊、抗压缩噪点更好）。 */
    private static float[][] smallDescs(List<CardMatcher.Template> tpl) {
        float[][] out = new float[tpl.size()][];
        for (int i = 0; i < tpl.size(); i++) {
            float[] full = tpl.get(i).full;
            float[] o = new float[DESC_W * DESC_H];
            for (int y = 0; y < DESC_H; y++) {
                for (int x = 0; x < DESC_W; x++) {
                    int a = (y * 2) * CardMatcher.FULL_W + x * 2;
                    o[y * DESC_W + x] = (full[a] + full[a + 1]
                            + full[a + CardMatcher.FULL_W] + full[a + CardMatcher.FULL_W + 1]) * 0.25f;
                }
            }
            normalize(o);
            out[i] = o;
        }
        return out;
    }

    private static void sampleGray(float[] g, int gw, int gh, float x0, float y0, float x1, float y1,
                                   float[] out) {
        float dx = (x1 - x0) / DESC_W, dy = (y1 - y0) / DESC_H;
        for (int oy = 0; oy < DESC_H; oy++) {
            int iy0 = (int) Math.floor(y0 + oy * dy);
            int iy1 = (int) Math.ceil(y0 + (oy + 1) * dy);
            iy0 = Math.max(0, Math.min(gh - 1, iy0));
            iy1 = Math.max(iy0 + 1, Math.min(gh, iy1));
            for (int ox = 0; ox < DESC_W; ox++) {
                int ix0 = (int) Math.floor(x0 + ox * dx);
                int ix1 = (int) Math.ceil(x0 + (ox + 1) * dx);
                ix0 = Math.max(0, Math.min(gw - 1, ix0));
                ix1 = Math.max(ix0 + 1, Math.min(gw, ix1));
                float sum = 0;
                for (int y = iy0; y < iy1; y++) {
                    int base = y * gw;
                    for (int x = ix0; x < ix1; x++) sum += g[base + x];
                }
                out[oy * DESC_W + ox] = sum / ((iy1 - iy0) * (ix1 - ix0));
            }
        }
    }

    /** 描述子旋转 180°（对手的卡在 MD 里是倒着的）。 */
    private static void reverse180(float[] v) {
        float[] t = new float[v.length];
        for (int y = 0; y < DESC_H; y++) {
            for (int x = 0; x < DESC_W; x++) {
                t[y * DESC_W + x] = v[(DESC_H - 1 - y) * DESC_W + (DESC_W - 1 - x)];
            }
        }
        System.arraycopy(t, 0, v, 0, v.length);
    }

    private static void normalize(float[] v) {
        double mean = 0;
        for (float f : v) mean += f;
        mean /= v.length;
        double n = 0;
        for (int i = 0; i < v.length; i++) {
            v[i] -= mean;
            n += v[i] * v[i];
        }
        n = Math.sqrt(n);
        if (n < 1e-6) return;
        for (int i = 0; i < v.length; i++) v[i] /= n;
    }

    private static float dot(float[] a, float[] b) {
        float s = 0;
        for (int i = 0; i < a.length; i++) s += a[i] * b[i];
        return s;
    }
}
