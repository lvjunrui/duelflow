import com.duelflow.app.CardMatcher;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * 桌面端验证卡图模板匹配（不需要 Android）：
 *   javac -d /tmp/dfout app/src/main/java/com/duelflow/app/CardMatcher.java test/java/MatcherTest.java
 *   java -cp /tmp/dfout MatcherTest app/src/main/assets/cards
 *
 * 做法：用同一批卡图合成「类似 MD 画面」的测试屏（暗底 + 随机卡位 + 随机大小），
 * 再跑匹配器，检查召回率 / 误报 / 耗时。
 */
public class MatcherTest {

    static final int W = 1080, H = 2400;

    public static void main(String[] args) throws Exception {
        String dir = args.length > 0 ? args[0] : "app/src/main/assets/cards";
        File[] files = new File(dir).listFiles((d, n) -> n.endsWith(".jpg"));
        if (files == null || files.length == 0) {
            System.out.println("找不到卡图：" + dir);
            System.exit(2);
        }
        Arrays.sort(files);
        List<CardMatcher.Template> templates = new ArrayList<>();
        List<BufferedImage> imgs = new ArrayList<>();
        for (File f : files) {
            BufferedImage im = ImageIO.read(f);
            int[] px = im.getRGB(0, 0, im.getWidth(), im.getHeight(), null, 0, im.getWidth());
            int[] size = new int[2];
            float[] gray = CardMatcher.toGray(px, im.getWidth(), im.getHeight(), im.getWidth(), size);
            String name = f.getName();
            templates.add(CardMatcher.Template.build(templates.size(), name, gray, im.getWidth(), im.getHeight()));
            imgs.add(im);
        }
        System.out.println("模板 " + templates.size() + " 张");

        int totalTrue = 0, totalHit = 0, totalFalse = 0;
        long msSum = 0;
        String[][] cases = {{"清晰", "0"}, {"模糊+噪点", "1"}, {"小卡偏暗", "2"}};
        for (String[] c : cases) {
            Scenario sc = buildScene(Integer.parseInt(c[1]), dir);
            long t0 = System.currentTimeMillis();
            List<CardMatcher.Hit> hits = CardMatcher.match(sc.pixels, W, H, templates);
            long ms = System.currentTimeMillis() - t0;
            msSum += ms;
            int hit = 0;
            for (Truth t : sc.truth) {
                CardMatcher.Hit best = null;
                for (CardMatcher.Hit h : hits) {
                    double dx = h.cx - t.cx, dy = h.cy - t.cy;
                    if (Math.hypot(dx, dy) < t.cw * 0.35 && h.name != null && h.name.equals(t.file)) {
                        best = h;
                        break;
                    }
                }
                if (best != null) hit++;
            }
            int falsePos = 0;
            for (CardMatcher.Hit h : hits) {
                boolean ok = false;
                for (Truth t : sc.truth) {
                    if (Math.hypot(h.cx - t.cx, h.cy - t.cy) < t.cw * 0.35) {
                        ok = true;
                        break;
                    }
                }
                if (!ok && h.name != null) falsePos++;
            }
            totalTrue += sc.truth.size();
            totalHit += hit;
            totalFalse += falsePos;
            System.out.printf("%s：真值 %d，命中 %d，误报 %d，耗时 %d ms%n",
                    c[0], sc.truth.size(), hit, falsePos, ms);
            for (Truth t : sc.truth) {
                System.out.printf("   真值 %s @%.0f,%.0f w=%.0f%n", t.file, t.cx, t.cy, t.cw);
            }
            for (CardMatcher.Hit h : hits) {
                System.out.println("   命中 " + h);
            }
        }
        double recall = totalTrue == 0 ? 0 : (double) totalHit / totalTrue;
        System.out.printf("总召回 %.0f%%（%d/%d），误报 %d，平均 %d ms%n",
                recall * 100, totalHit, totalTrue, totalFalse, msSum / cases.length);
        if (recall < 0.8) {
            System.out.println("失败：召回率低于 80%");
            System.exit(1);
        }
        if (totalFalse > 0) {
            System.out.println("失败：有误报");
            System.exit(1);
        }
        System.out.println("通过");
    }

    static class Truth {
        String file;
        float cx, cy, cw, ch;
    }

    static class Scenario {
        int[] pixels;
        List<Truth> truth = new ArrayList<>();
    }

    static Scenario buildScene(int mode, String dir) throws Exception {
        Random rnd = new Random(20261121L + mode);
        Scenario sc = new Scenario();
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        /* 暗底 + 渐变 + 柔和光斑，模拟对局背景（避免画成卡片大小的方块） */
        for (int y = 0; y < H; y++) {
            float k = 0.5f + 0.5f * (float) Math.sin(y * Math.PI / H);
            g.setColor(new Color((int) (22 + 10 * k), (int) (28 + 12 * k), (int) (36 + 14 * k)));
            g.fillRect(0, y, W, 1);
        }
        g.setPaint(new java.awt.RadialGradientPaint(new java.awt.geom.Point2D.Float(W * 0.3f, H * 0.35f),
                420f, new float[]{0f, 1f}, new Color[]{new Color(70, 60, 40, 120), new Color(0, 0, 0, 0)}));
        g.fillOval(-200, (int) (H * 0.35) - 420, 1240, 840);
        g.setColor(new Color(200, 170, 90, 60));
        g.fillRect(0, H / 2 - 4, W, 8);

        File[] files = new File(dir).listFiles((d, n) -> n.endsWith(".jpg"));
        Arrays.sort(files);

        float scaleMin = mode == 2 ? 0.085f : 0.10f;
        float scaleMax = mode == 2 ? 0.115f : 0.175f;
        /* 对方场地 5 张、我方 3 张、手牌 4 张 */
        int[][] rows = {{330, 520, 5}, {1420, 1620, 3}, {2120, 2260, 4}};
        int pick = 0;
        for (int[] row : rows) {
            int n = row[2];
            for (int i = 0; i < n; i++) {
                int idx = (pick * 7 + mode) % files.length;
                BufferedImage card = ImageIO.read(files[idx]);
                String sname = files[idx].getName();
                pick++;
                float cw = W * (scaleMin + rnd.nextFloat() * (scaleMax - scaleMin));
                float ch = cw * 1.457f;
                float gap = (W - 80f) / n;
                float cx = 40 + gap * (i + 0.5f) + (rnd.nextFloat() - 0.5f) * gap * 0.15f;
                float cy = row[0] + rnd.nextFloat() * (row[1] - row[0]);
                int x = Math.round(cx - cw / 2), y = Math.round(cy - ch / 2);
                if (x < 0 || y < 0 || x + cw >= W || y + ch >= H) continue;
                g.drawImage(card, x, y, Math.round(cw), Math.round(ch), null);
                Truth t = new Truth();
                t.file = sname;
                t.cx = cx;
                t.cy = cy;
                t.cw = cw;
                t.ch = ch;
                sc.truth.add(t);
            }
        }
        g.dispose();

        sc.pixels = img.getRGB(0, 0, W, H, null, 0, W);
        if (mode >= 1) {
            /* 轻微模糊 + 噪点，模拟视频压缩与截屏损失 */
            boxBlur(sc.pixels, W, H, 1);
            Random r2 = new Random(7 + mode);
            for (int i = 0; i < sc.pixels.length; i++) {
                int c = sc.pixels[i];
                int n = (int) Math.round(r2.nextGaussian() * 5);
                int r = clamp(((c >> 16) & 0xff) + n);
                int gg = clamp(((c >> 8) & 0xff) + n);
                int b = clamp((c & 0xff) + n);
                sc.pixels[i] = (0xff << 24) | (r << 16) | (gg << 8) | b;
            }
        }
        if (mode == 2) {
            /* 整体压暗 */
            for (int i = 0; i < sc.pixels.length; i++) {
                int c = sc.pixels[i];
                int r = (int) (((c >> 16) & 0xff) * 0.72);
                int gg = (int) (((c >> 8) & 0xff) * 0.72);
                int b = (int) ((c & 0xff) * 0.72);
                sc.pixels[i] = (0xff << 24) | (r << 16) | (gg << 8) | b;
            }
        }
        return sc;
    }

    static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    static void boxBlur(int[] px, int w, int h, int r) {
        int[] src = px.clone();
        int d = 2 * r + 1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int sr = 0, sg = 0, sb = 0, n = 0;
                for (int dy = -r; dy <= r; dy++) {
                    int yy = y + dy;
                    if (yy < 0 || yy >= h) continue;
                    for (int dx = -r; dx <= r; dx++) {
                        int xx = x + dx;
                        if (xx < 0 || xx >= w) continue;
                        int c = src[yy * w + xx];
                        sr += (c >> 16) & 0xff;
                        sg += (c >> 8) & 0xff;
                        sb += c & 0xff;
                        n++;
                    }
                }
                int cr = sr / n, cg = sg / n, cb = sb / n;
                px[y * w + x] = (0xff << 24) | (cr << 16) | (cg << 8) | cb;
            }
        }
    }
}
