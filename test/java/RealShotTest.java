import com.duelflow.app.CardMatcher;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 用真实 MD 截图量模板匹配耗时与命中（桌面 JVM，模拟手机上的工作负载）。 */
public class RealShotTest {

    public static void main(String[] args) throws Exception {
        String cardDir = args.length > 0 ? args[0] : "app/src/main/assets/cards";
        String shot = args.length > 1 ? args[1] : ".shot.jpg";
        float[] factors = null;
        if (args.length > 2) {
            String[] ps = args[2].split(",");
            factors = new float[ps.length];
            for (int i = 0; i < ps.length; i++) factors[i] = Float.parseFloat(ps[i]);
        }

        File[] files = new File(cardDir).listFiles((d, n) -> n.endsWith(".jpg"));
        Arrays.sort(files);
        List<CardMatcher.Template> templates = new ArrayList<>();
        long t0 = System.currentTimeMillis();
        for (File f : files) {
            BufferedImage im = ImageIO.read(f);
            int[] px = im.getRGB(0, 0, im.getWidth(), im.getHeight(), null, 0, im.getWidth());
            int[] size = new int[2];
            float[] gray = CardMatcher.toGray(px, im.getWidth(), im.getHeight(), im.getWidth(), size);
            templates.add(CardMatcher.Template.build(templates.size(), f.getName(), gray, im.getWidth(), im.getHeight()));
        }
        System.out.println("模板 " + templates.size() + " 张, 建库 " + (System.currentTimeMillis() - t0) + " ms");

        BufferedImage img = ImageIO.read(new File(shot));
        int w = img.getWidth(), h = img.getHeight();
        int[] px = img.getRGB(0, 0, w, h, null, 0, w);
        System.out.println("截图 " + w + "x" + h);

        CardMatcher.Options o = new CardMatcher.Options();
        if (factors != null) o.widthFactors = factors;

        for (int round = 0; round < 3; round++) {
            long a = System.currentTimeMillis();
            List<CardMatcher.Hit> hits = CardMatcher.match(px, w, h, templates, o);
            long ms = System.currentTimeMillis() - a;
            System.out.println("第" + (round + 1) + "轮 " + ms + " ms, 命中 " + hits.size());
            for (CardMatcher.Hit hh : hits) System.out.println("   " + hh);
        }
    }
}
