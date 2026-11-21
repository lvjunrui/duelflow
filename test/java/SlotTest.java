import com.duelflow.app.CardMatcher;
import com.duelflow.app.SlotRecognizer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * 手牌卡位识别测试。
 *  - 不带第二参数：合成场景自检（CI 跑这个），失败会 exit 1
 *  - 带截图路径：跑真机帧，打印每个卡位的识别结果（人工核对用）
 */
public class SlotTest {

    static final int W = 2712, H = 1220;

    public static void main(String[] a) throws Exception {
        String dir = a.length > 0 ? a[0] : "app/src/main/assets/cards";
        File[] files = new File(dir).listFiles((d, n) -> n.endsWith(".jpg"));
        Arrays.sort(files);
        List<CardMatcher.Template> tpl = new ArrayList<>();
        List<BufferedImage> imgs = new ArrayList<>();
        for (File f : files) {
            BufferedImage im = ImageIO.read(f);
            imgs.add(im);
            int w = im.getWidth(), h = im.getHeight();
            int[] px = im.getRGB(0, 0, w, h, null, 0, w);
            int[] size = new int[2];
            float[] gray = CardMatcher.toGray(px, w, h, w, size);
            tpl.add(CardMatcher.Template.build(tpl.size(), f.getName(), gray, w, h));
        }
        System.out.println("模板 " + tpl.size() + " 张");

        if (a.length > 1) {
            realFrame(a[1], tpl);
            return;
        }
        synthetic(tpl, imgs);
    }

    private static void realFrame(String path, List<CardMatcher.Template> tpl) throws Exception {
        BufferedImage img = ImageIO.read(new File(path));
        int w = img.getWidth(), h = img.getHeight();
        int[] px = img.getRGB(0, 0, w, h, null, 0, w);
        long t0 = System.currentTimeMillis();
        List<SlotRecognizer.Slot> slots = SlotRecognizer.hand(px, w, h, tpl);
        long ms = System.currentTimeMillis() - t0;
        System.out.println(w + "x" + h + "  " + ms + "ms  " + slots.size() + " 个卡位");
        for (SlotRecognizer.Slot s : slots) {
            System.out.println("   " + (s.confident() ? "✓" : "·") + " " + s
                    + String.format("  margin=%.3f", s.margin));
        }
        long t1 = System.currentTimeMillis();
        List<SlotRecognizer.Slot> field = SlotRecognizer.field(px, w, h, tpl);
        System.out.println("场上 " + (System.currentTimeMillis() - t1) + "ms  认出 " + field.size() + " 张");
        for (SlotRecognizer.Slot s : field) {
            System.out.println("   " + s.side + " " + s + String.format("  margin=%.3f", s.margin));
        }
    }

    /** 合成一帧 2712×1220 的手牌画面，检查卡位几何 + 认卡是否跑通。 */
    private static void synthetic(List<CardMatcher.Template> tpl, List<BufferedImage> imgs) {
        int failed = 0;

        /* 空场（只有背景）：不应认出任何卡 */
        int[] emptyBg = background();
        List<SlotRecognizer.Slot> none = SlotRecognizer.hand(emptyBg, W, H, tpl);
        int conf = 0;
        for (SlotRecognizer.Slot s : none) if (s.confident()) conf++;
        System.out.println("空背景：识别 " + none.size() + " 个卡位，认出 " + conf + " 张");
        if (conf != 0) {
            System.out.println("  ✗ 空背景不该认出卡");
            failed++;
        } else {
            System.out.println("  ✓ 空背景零误报");
        }

        /* 3 / 5 / 6 张手牌：都要认对 */
        for (int n : new int[]{3, 5, 6}) {
            int[] px = background();
            String[] want = new String[n];
            BufferedImage canvas = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
            canvas.setRGB(0, 0, W, H, px, 0, W);
            float cardW = SlotRecognizer.HAND_CARD_W * W;
            float pitch = SlotRecognizer.HAND_PITCH * W;
            if (n > 5) {
                float fanMax = 0.40f * W;
                float p = (fanMax - cardW) / (n - 1);
                if (p > 0 && p < pitch) pitch = p;
            }
            float cardH = cardW * SlotRecognizer.CARD_ASPECT;
            float fanW = cardW + (n - 1) * pitch;
            float firstX = (float) (W / 2.0 - fanW / 2.0);
            float top = SlotRecognizer.HAND_TOP * H;
            Random rnd = new Random(17 + n);
            for (int i = 0; i < n; i++) {
                int pick = (i * 7 + n * 3) % imgs.size();
                want[i] = tpl.get(pick).name;
                java.awt.Graphics2D g = canvas.createGraphics();
                int jx = rnd.nextInt(7) - 3, jy = rnd.nextInt(7) - 3;
                g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                        java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(imgs.get(pick), Math.round(firstX + i * pitch) + jx,
                        Math.round(top) + jy, Math.round(cardW), Math.round(cardH), null);
                g.dispose();
            }
            int[] px2 = canvas.getRGB(0, 0, W, H, null, 0, W);
            long t0 = System.currentTimeMillis();
            List<SlotRecognizer.Slot> slots = SlotRecognizer.hand(px2, W, H, tpl);
            long ms = System.currentTimeMillis() - t0;
            System.out.println(n + " 张手牌：" + ms + "ms 识别出 " + slots.size() + " 个卡位");
            boolean ok = slots.size() == n;
            for (int i = 0; i < slots.size(); i++) {
                SlotRecognizer.Slot s = slots.get(i);
                boolean good = ok && i < n && want[i].equals(s.name);
                System.out.println("   " + (good ? "✓" : "✗") + " " + s
                        + (good ? "" : "  期望 " + (i < n ? want[i] : "-")));
                if (!good) ok = false;
            }
            if (!ok) failed++;
        }

        System.out.println(failed == 0 ? "\n全部通过" : "\n失败 " + failed + " 项");
        if (failed != 0) System.exit(1);
    }

    /** 一块带噪点的深色背景（避免和任何模板偶然高相关）。 */
    private static int[] background() {
        int[] px = new int[W * H];
        Random rnd = new Random(7);
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int base = 28 + (int) (18 * Math.sin(x * 0.004) * Math.cos(y * 0.003));
                int n = rnd.nextInt(14) - 7;
                int v = Math.max(0, Math.min(255, base + n));
                px[y * W + x] = (v << 16) | ((v + 4) << 8) | (v + 10);
            }
        }
        return px;
    }
}
