import com.duelflow.app.CardMatcher;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** 真实 MD 截图上的模板匹配校准工具（不进 CI）。
 *  用法：java CalibTest <cards目录> <截图.jpg> [workWidth] [acceptScore] [widthFactors逗号分隔] [montage.png] */
public class CalibTest {

    public static void main(String[] a) throws Exception {
        String cardDir = a[0];
        String shot = a[1];
        int workWidth = a.length > 2 ? Integer.parseInt(a[2]) : 1080;
        float accept = a.length > 3 ? Float.parseFloat(a[3]) : 0.75f;
        float[] factors = null;
        if (a.length > 4 && !a[4].isEmpty()) {
            String[] ps = a[4].split(",");
            factors = new float[ps.length];
            for (int i = 0; i < ps.length; i++) factors[i] = Float.parseFloat(ps[i]);
        }
        String montage = a.length > 5 ? a[5] : "/tmp/calib.png";

        File[] files = new File(cardDir).listFiles((d, n) -> n.endsWith(".jpg"));
        Arrays.sort(files);
        List<CardMatcher.Template> tpl = new ArrayList<>();
        for (File f : files) {
            BufferedImage im = ImageIO.read(f);
            int[] px = im.getRGB(0, 0, im.getWidth(), im.getHeight(), null, 0, im.getWidth());
            int[] size = new int[2];
            float[] g = CardMatcher.toGray(px, im.getWidth(), im.getHeight(), 400, size);
            tpl.add(CardMatcher.Template.build(tpl.size(), f.getName(), g, size[0], size[1]));
        }
        System.out.println("模板 " + tpl.size() + " 张");

        BufferedImage img = ImageIO.read(new File(shot));
        int W = img.getWidth(), H = img.getHeight();
        int[] px = img.getRGB(0, 0, W, H, null, 0, W);

        CardMatcher.Options o = new CardMatcher.Options();
        o.workWidth = workWidth;
        o.acceptScore = accept;
        if (factors != null) o.widthFactors = factors;

        long t0 = System.currentTimeMillis();
        int[] size = new int[2];
        float[] work = CardMatcher.toGray(px, W, H, workWidth, size);
        long t1 = System.currentTimeMillis();
        List<CardMatcher.Hit> hits = CardMatcher.matchGray(work, size[0], size[1], tpl, o, (float) W / size[0]);
        long t2 = System.currentTimeMillis();
        System.out.println("灰度 " + (t1 - t0) + "ms  匹配 " + (t2 - t1) + "ms  工作图 " + size[0] + "x" + size[1]);
        System.out.println("命中 " + hits.size() + " 个");

        /* 自己的采样器，用来列出每个命中框的前 3 名模板（含未达阈值的情况） */
        Integral ig = new Integral(work, size[0], size[1]);
        float scale = (float) W / size[0];

        List<CardMatcher.Hit> sorted = new ArrayList<>(hits);
        sorted.sort(new Comparator<CardMatcher.Hit>() {
            public int compare(CardMatcher.Hit x, CardMatcher.Hit y) {
                return Float.compare(y.score, x.score);
            }
        });

        List<int[]> boxes = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (CardMatcher.Hit h : sorted) {
            float cw = h.cw / scale, ch = h.ch / scale;
            float cx = h.cx / scale, cy = h.cy / scale;
            float ax0 = cx - cw / 2f + CardMatcher.ART_X0 * cw;
            float ay0 = cy - ch / 2f + CardMatcher.ART_Y0 * ch;
            float ax1 = cx - cw / 2f + CardMatcher.ART_X1 * cw;
            float ay1 = cy - ch / 2f + CardMatcher.ART_Y1 * ch;
            if (ax0 < 0 || ay0 < 0 || ax1 > size[0] || ay1 > size[1]) continue;
            float[] win = sample(ig, ax0, ay0, ax1, ay1, CardMatcher.FULL_W, CardMatcher.FULL_H);
            norm(win);
            float[] sc = new float[tpl.size()];
            for (int i = 0; i < tpl.size(); i++) sc[i] = dot(win, tpl.get(i).full);
            Integer[] idx = new Integer[tpl.size()];
            for (int i = 0; i < idx.length; i++) idx[i] = i;
            Arrays.sort(idx, (x, y) -> Float.compare(sc[y], sc[x]));
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("%.2f", h.score)).append("  ").append(h.name == null ? "?" : h.name)
                    .append("  @").append(Math.round(h.cx)).append(",").append(Math.round(h.cy))
                    .append(" w=").append(Math.round(h.cw));
            System.out.println("  #" + (boxes.size() + 1) + " " + sb);
            for (int k = 0; k < 3; k++) {
                System.out.println("        top" + (k + 1) + " " + String.format("%.3f", sc[idx[k]])
                        + "  " + tpl.get(idx[k]).name);
            }
            boxes.add(new int[]{Math.round(cx - cw / 2), Math.round(cy - ch / 2),
                    Math.round(cw), Math.round(ch), idx[0], idx[1], idx[2]});
            labels.add(sb.toString());
        }

        /* 拼图：每个命中一行，第一格是截图里的卡，后面是前 3 名模板原图 */
        int cellW = 132, cellH = 192, rows = boxes.size();
        if (rows == 0) rows = 1;
        BufferedImage out = new BufferedImage(cellW * 4 + 8, rows * (cellH + 22) + 8 + 22, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(new Color(24, 24, 28));
        g.fillRect(0, 0, out.getWidth(), out.getHeight());
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.PLAIN, 14));
        g.drawString("工作图 " + size[0] + "x" + size[1] + " 命中 " + hits.size()
                + "  匹配 " + (t2 - t1) + "ms  accept=" + accept, 6, 18);
        for (int r = 0; r < boxes.size(); r++) {
            int[] b = boxes.get(r);
            int y = 26 + r * (cellH + 22);
            /* 截图裁片 */
            int surfW = Math.max(1, b[2]), surfH = Math.max(1, b[3]);
            BufferedImage src = new BufferedImage(surfW, surfH, BufferedImage.TYPE_INT_RGB);
            for (int yy = 0; yy < surfH; yy++) {
                for (int xx = 0; xx < surfW; xx++) {
                    int sx = Math.min(W - 1, Math.max(0, b[0] + xx));
                    int sy = Math.min(H - 1, Math.max(0, b[1] + yy));
                    src.setRGB(xx, yy, px[sy * W + sx]);
                }
            }
            g.drawImage(src, 6, y, cellW, cellH, null);
            g.setColor(new Color(80, 130, 255));
            g.setStroke(new BasicStroke(2f));
            g.drawRect(6, y, cellW, cellH);
            for (int k = 0; k < 3; k++) {
                BufferedImage t = ImageIO.read(new File(cardDir, tpl.get(b[4 + k]).name));
                g.drawImage(t, 6 + (k + 1) * cellW, y, cellW, cellH, null);
            }
            g.setColor(Color.WHITE);
            g.setFont(new Font("SansSerif", Font.PLAIN, 11));
            g.drawString(labels.get(r), 6, y + cellH + 14);
        }
        g.dispose();
        ImageIO.write(out, "png", new File(montage));
        System.out.println("拼图 → " + montage);
    }

    /* ---------------- 简易积分图采样（与 CardMatcher 同算法） ---------------- */

    static final class Integral {
        final int w, h;
        final double[] s, s2;

        Integral(float[] g, int w, int h) {
            this.w = w;
            this.h = h;
            s = new double[(w + 1) * (h + 1)];
            s2 = new double[(w + 1) * (h + 1)];
            for (int y = 0; y < h; y++) {
                double rs = 0, rs2 = 0;
                for (int x = 0; x < w; x++) {
                    double v = g[y * w + x];
                    rs += v;
                    rs2 += v * v;
                    s[(y + 1) * (w + 1) + x + 1] = s[y * (w + 1) + x + 1] + rs;
                    s2[(y + 1) * (w + 1) + x + 1] = s2[y * (w + 1) + x + 1] + rs2;
                }
            }
        }

        double sum(int x0, int y0, int x1, int y1) {
            return s[y1 * (w + 1) + x1] - s[y0 * (w + 1) + x1] - s[y1 * (w + 1) + x0] + s[y0 * (w + 1) + x0];
        }

        double sum2(int x0, int y0, int x1, int y1) {
            return s2[y1 * (w + 1) + x1] - s2[y0 * (w + 1) + x1] - s2[y1 * (w + 1) + x0] + s2[y0 * (w + 1) + x0];
        }
    }

    static float[] sample(Integral ig, float x0, float y0, float x1, float y1, int ow, int oh) {
        float[] out = new float[ow * oh];
        for (int oy = 0; oy < oh; oy++) {
            float ay0 = y0 + (y1 - y0) * oy / oh, ay1 = y0 + (y1 - y0) * (oy + 1) / oh;
            for (int ox = 0; ox < ow; ox++) {
                float ax0 = x0 + (x1 - x0) * ox / ow, ax1 = x0 + (x1 - x0) * (ox + 1) / ow;
                int ix0 = Math.max(0, (int) Math.floor(ax0)), ix1 = Math.max(ix0 + 1, (int) Math.ceil(ax1));
                int iy0 = Math.max(0, (int) Math.floor(ay0)), iy1 = Math.max(iy0 + 1, (int) Math.ceil(ay1));
                ix1 = Math.min(ig.w, ix1);
                iy1 = Math.min(ig.h, iy1);
                double area = (double) (ix1 - ix0) * (iy1 - iy0);
                out[oy * ow + ox] = area > 0 ? (float) (ig.sum(ix0, iy0, ix1, iy1) / area) : 0f;
            }
        }
        return out;
    }

    static void norm(float[] v) {
        double n = 0;
        double mean = 0;
        for (float f : v) mean += f;
        mean /= v.length;
        for (int i = 0; i < v.length; i++) {
            v[i] -= mean;
            n += v[i] * v[i];
        }
        n = Math.sqrt(n);
        if (n < 1e-6) return;
        for (int i = 0; i < v.length; i++) v[i] /= n;
    }

    static float dot(float[] a, float[] b) {
        float s = 0;
        for (int i = 0; i < a.length; i++) s += a[i] * b[i];
        return s;
    }
}
