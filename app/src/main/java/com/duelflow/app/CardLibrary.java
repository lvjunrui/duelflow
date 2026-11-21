package com.duelflow.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 卡图模板库：从 assets/cards/ 读 manifest + 小图，构建匹配模板。
 * 只在首次识别时加载一次，之后常驻内存（约 49 张 × 几十 KB 的中间数组）。
 */
public final class CardLibrary {

    public static final class Card {
        public final int cardId;
        public final String cn;
        public final String en;
        public final String kind;
        public final int atk;

        Card(int cardId, String cn, String en, String kind, int atk) {
            this.cardId = cardId;
            this.cn = cn;
            this.en = en;
            this.kind = kind;
            this.atk = atk;
        }
    }

    private static CardLibrary instance;

    public final List<Card> cards;
    public final List<CardMatcher.Template> templates;

    private CardLibrary(List<Card> cards, List<CardMatcher.Template> templates) {
        this.cards = Collections.unmodifiableList(cards);
        this.templates = Collections.unmodifiableList(templates);
    }

    public static synchronized CardLibrary get(Context ctx) {
        if (instance == null) {
            instance = load(ctx.getApplicationContext());
        }
        return instance;
    }

    private static CardLibrary load(Context ctx) {
        List<Card> cards = new ArrayList<>();
        List<CardMatcher.Template> templates = new ArrayList<>();
        try {
            String json = readAsset(ctx, "cards/manifest.json");
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String file = o.optString("file");
                if (file.isEmpty()) continue;
                Bitmap bmp = null;
                try {
                    InputStream in = ctx.getAssets().open("cards/" + file);
                    bmp = BitmapFactory.decodeStream(in);
                    in.close();
                } catch (Throwable ignored) {
                }
                if (bmp == null) continue;
                int w = bmp.getWidth(), h = bmp.getHeight();
                int[] px = new int[w * h];
                bmp.getPixels(px, 0, w, 0, 0, w, h);
                bmp.recycle();
                int[] size = new int[2];
                float[] gray = CardMatcher.toGray(px, w, h, w, size);
                Card c = new Card(o.optInt("id"), o.optString("cn"), o.optString("en"),
                        o.optString("kind"), o.optInt("atk"));
                cards.add(c);
                templates.add(CardMatcher.Template.build(cards.size() - 1, c.cn, gray, w, h));
            }
        } catch (Throwable ignored) {
        }
        return new CardLibrary(cards, templates);
    }

    private static String readAsset(Context ctx, String name) throws Exception {
        InputStream in = ctx.getAssets().open(name);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return new String(bos.toByteArray(), "UTF-8");
    }
}
