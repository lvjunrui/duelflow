package com.duelflow.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 悬浮窗服务：MediaProjection 截屏 + 端侧中文 OCR，把识别结果交给浮窗里的 WebView 决策。
 * 完全离线，不申请网络权限。
 */
public class OverlayService extends Service {

    public static final String ACTION_START = "com.duelflow.app.ACTION_START_OVERLAY";
    public static final String EXTRA_CODE = "code";
    public static final String EXTRA_DATA = "data";

    private static final String TAG = "DuelFlowOverlay";
    private static final String CHANNEL = "df_overlay";
    private static final int NOTIF_ID = 0x51;

    private WindowManager wm;
    private View root;
    private WebView web;
    private WindowManager.LayoutParams lp;
    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private TextRecognizer ocr;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean capturing;
    private int captureSeq;
    private volatile String frameNote;
    /** 一次识别有两路结果（卡图 / 文字），两路都到齐才算结束 */
    private final AtomicInteger legs = new AtomicInteger(0);
    private int screenW, screenH, screenDpi;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_START.equals(intent.getAction())) {
            int code = intent.getIntExtra(EXTRA_CODE, 0);
            Intent data = intent.getParcelableExtra(EXTRA_DATA);
            startForegroundSafely();
            if (data != null) {
                startProjection(code, data);
            } else {
                postError("缺少投屏授权，请重新开启浮窗");
            }
            showOverlay();
        }
        return START_NOT_STICKY;
    }

    /* ---------------- 前台通知 ---------------- */

    private void startForegroundSafely() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "浮窗识别", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        b.setContentTitle("DuelFlow 浮窗已开启")
                .setContentText("点浮窗里的「识别屏幕」抓取当前画面")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true);
        Notification n = b.build();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Throwable t) {
            Log.w(TAG, "startForeground(mediaProjection) failed, fallback", t);
            try {
                startForeground(NOTIF_ID, n);
            } catch (Throwable ignored) {
            }
        }
    }

    /* ---------------- 截屏通道 ---------------- */

    private void startProjection(int code, Intent data) {
        try {
            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            projection = mpm.getMediaProjection(code, data);
        } catch (Throwable t) {
            Log.w(TAG, "getMediaProjection failed", t);
        }
        if (projection == null) {
            postError("投屏授权失败，请重新开启浮窗");
            stopSelf();
            return;
        }
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                main.post(() -> stopSelf());
            }

            /** 共享「单个应用」或进入画中画时系统会给新尺寸，跟着改截屏尺寸。 */
            @Override
            public void onCapturedContentResize(int width, int height) {
                main.post(() -> {
                    try {
                        ensureCapture(width, height, screenDpi > 0 ? screenDpi : 440);
                    } catch (Throwable t) {
                        Log.w(TAG, "resize capture failed", t);
                    }
                });
            }
        }, main);

        ocr = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
        /* 预热卡图库：放到后台线程，第一次识别不用再等 49 张卡图解图 */
        worker.execute(() -> {
            try {
                CardLibrary.get(this);
            } catch (Throwable t) {
                Log.w(TAG, "warm card library failed", t);
            }
        });
        try {
            refreshCaptureSize();
            postStatus("录屏通道就绪 " + screenW + "×" + screenH);
        } catch (Throwable t) {
            Log.w(TAG, "virtual display failed", t);
            postError("建立截屏通道失败：" + t.getClass().getSimpleName() + "，请重开浮窗");
        }
    }

    /** 按当前真实屏幕尺寸（已考虑横竖屏）建立或调整截屏通道。 */
    private void refreshCaptureSize() {
        DisplayMetrics dm = new DisplayMetrics();
        Object sys = getSystemService(WINDOW_SERVICE);
        if (sys instanceof WindowManager) {
            ((WindowManager) sys).getDefaultDisplay().getRealMetrics(dm);
        }
        int w = dm.widthPixels > 0 ? dm.widthPixels : 1080;
        int h = dm.heightPixels > 0 ? dm.heightPixels : 1920;
        int dpi = dm.densityDpi > 0 ? dm.densityDpi : 440;
        ensureCapture(w, h, dpi);
    }

    /**
     * 尺寸变了（旋转屏幕、游戏从竖屏切横屏）就换一个 ImageReader 并 resize 同一个 VirtualDisplay。
     * 不新建 VirtualDisplay：Android 14 起一个 MediaProjection 只能建一个。
     */
    private void ensureCapture(int w, int h, int dpi) {
        if (projection == null) throw new IllegalStateException("projection null");
        if (w <= 0 || h <= 0) throw new IllegalStateException("bad size " + w + "x" + h);
        if (reader != null && w == screenW && h == screenH) return;
        ImageReader nr = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
        try {
            if (display == null) {
                display = projection.createVirtualDisplay("df-capture", w, h, dpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        nr.getSurface(), null, main);
            } else {
                display.setSurface(nr.getSurface());
                display.resize(w, h, dpi);
            }
        } catch (RuntimeException e) {
            try {
                nr.close();
            } catch (Throwable ignored) {
            }
            throw e;
        }
        ImageReader old = reader;
        reader = nr;
        screenW = w;
        screenH = h;
        screenDpi = dpi;
        if (old != null) {
            try {
                old.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /* ---------------- 悬浮窗 ---------------- */

    private int dp(float v) {
        return (int) (getResources().getDisplayMetrics().density * v + 0.5f);
    }

    private void showOverlay() {
        if (root != null) return;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        lp = new WindowManager.LayoutParams(
                dp(312),
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = dp(12);
        lp.y = dp(160);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(0xFF12161D);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xFF1B222C);
        bar.setPadding(dp(10), dp(8), dp(4), dp(8));

        TextView title = new TextView(this);
        title.setText("DuelFlow 浮窗");
        title.setTextColor(0xFF9FB0C8);
        title.setTextSize(11);
        bar.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView close = new TextView(this);
        close.setText("✕");
        close.setTextColor(0xFFE05B6A);
        close.setTextSize(14);
        close.setPadding(dp(8), 0, dp(8), 0);
        close.setOnClickListener(v -> stopSelf());
        bar.addView(close);

        bar.setOnTouchListener(new View.OnTouchListener() {
            private float downX, downY;
            private int startX, startY;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        startX = lp.x;
                        startY = lp.y;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        lp.x = Math.max(0, startX + (int) (e.getRawX() - downX));
                        lp.y = Math.max(0, startY + (int) (e.getRawY() - downY));
                        if (root != null) wm.updateViewLayout(root, lp);
                        return true;
                    default:
                        return false;
                }
            }
        });

        web = new WebView(getApplicationContext());
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setTextZoom(100);
        web.setBackgroundColor(0xFF12161D);
        web.addJavascriptInterface(new Bridge(), "DFNative");
        web.setWebViewClient(new WebViewClient());
        web.loadUrl("file:///android_asset/overlay.html");

        box.addView(bar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        box.addView(web, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(452)));

        root = box;
        try {
            wm.addView(root, lp);
        } catch (Throwable t) {
            Log.w(TAG, "addView failed", t);
            postError("没有悬浮窗权限，请先在设置里允许");
        }
    }

    private void setFocusable(boolean on) {
        if (root == null) return;
        if (on) {
            lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        } else {
            lp.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        }
        try {
            wm.updateViewLayout(root, lp);
        } catch (Throwable ignored) {
        }
    }

    /* ---------------- 抓图 + OCR ---------------- */

    private static final int WATCHDOG_MS = 20000;
    private static final String NO_FRAME_HINT =
            "没截到画面：请确认系统弹窗选的是「共享整个屏幕」，并把游戏切到前台后再点一次";

    private void captureOnce(boolean saveShot) {
        if (root == null || projection == null || ocr == null) {
            postError("识别通道未就绪：请关掉浮窗，回 App 重新点「浮窗」");
            return;
        }
        if (capturing) {
            postStatus("上一次识别还没结束，稍等一下");
            return;
        }
        capturing = true;
        final int gen = ++captureSeq;
        try {
            /* 每次抓图前都按当前屏幕重新对一次尺寸：MD 是横屏，浮窗多半是竖屏时开的 */
            refreshCaptureSize();
        } catch (Throwable t) {
            capturing = false;
            postError("截屏通道失效：" + t.getClass().getSimpleName() + "，请重开浮窗");
            return;
        }
        postStatus("正在截屏 " + screenW + "×" + screenH + "…");
        root.setVisibility(View.GONE);
        /* 等一帧，避免把自己的悬浮窗拍进去 */
        main.postDelayed(() -> {
            if (gen != captureSeq) return;
            worker.execute(() -> {
                Bitmap shot = null;
                try {
                    shot = grabLatest();
                } catch (Throwable t) {
                    Log.w(TAG, "grab failed", t);
                }
                final Bitmap bmp = shot;
                final String note = frameNote;
                main.post(() -> {
                    if (root != null) root.setVisibility(View.VISIBLE);
                });
                if (bmp == null) {
                    capturing = false;
                    postError(note != null ? note : NO_FRAME_HINT);
                    return;
                }
                postStatus("已截到 " + bmp.getWidth() + "×" + bmp.getHeight()
                        + (note != null ? "（" + note + "）" : "") + "，认卡中…");
                if (saveShot) savePng(bmp);
                try {
                    analyze(bmp);
                } catch (Throwable t) {
                    Log.w(TAG, "analyze failed", t);
                    capturing = false;
                    postError("分析画面失败：" + t.getClass().getSimpleName());
                } finally {
                    bmp.recycle();
                }
            });
        }, 220);
        /* 看门狗：不管卡在哪一步，都不能让浮窗永远停在「正在截屏」 */
        main.postDelayed(() -> {
            if (gen == captureSeq && capturing) {
                capturing = false;
                postError("识别超时（" + (WATCHDOG_MS / 1000) + " 秒没出结果）：再点一次，或关掉浮窗重开");
            }
        }, WATCHDOG_MS);
    }

    /**
     * 抓一帧新画面。
     * 先把 ImageReader 队列里攒的旧帧丢掉，再等这一瞬间的新帧；
     * 新帧的时间戳如果很旧，说明录屏画面根本没更新（多半是系统只共享了「单个应用」）。
     */
    private Bitmap grabLatest() {
        frameNote = null;
        ImageReader r = reader;
        if (r == null) {
            frameNote = "截屏通道没建立起来：请重开浮窗";
            return null;
        }
        Image img;
        while (true) {
            try {
                img = r.acquireLatestImage();
            } catch (Throwable t) {
                break;
            }
            if (img == null) break;
            try {
                img.close();
            } catch (Throwable ignored) {
            }
        }
        long deadline = System.currentTimeMillis() + 1800;
        Image fresh = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                fresh = r.acquireLatestImage();
            } catch (Throwable t) {
                break;
            }
            if (fresh != null) break;
            try {
                Thread.sleep(40);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (fresh == null) {
            frameNote = NO_FRAME_HINT;
            return null;
        }
        try {
            long age = System.nanoTime() - fresh.getTimestamp();
            if (age > 1500000000L && age < 600000000000L) {
                frameNote = "画面是 " + (age / 1000000000L) + " 秒前的，录屏可能没在更新";
            }
            Image.Plane plane = fresh.getPlanes()[0];
            ByteBuffer buf = plane.getBuffer();
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            int w = fresh.getWidth();
            int h = fresh.getHeight();
            int rowPadding = rowStride - pixelStride * w;
            Bitmap padded = Bitmap.createBitmap(w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888);
            padded.copyPixelsFromBuffer(buf);
            if (rowPadding == 0) return padded;
            Bitmap cropped = Bitmap.createBitmap(padded, 0, 0, w, h);
            padded.recycle();
            return cropped;
        } finally {
            try {
                fresh.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 截到一帧后的完整分析：模板匹配认卡（方案二）+ 分带放大 OCR（方案一）。 */
    private void analyze(Bitmap bmp) {
        final int w = bmp.getWidth(), h = bmp.getHeight();
        int[] px = new int[w * h];
        try {
            bmp.getPixels(px, 0, w, 0, 0, w, h);
        } catch (Throwable t) {
            capturing = false;
            postError("读取画面失败：" + t.getClass().getSimpleName());
            return;
        }
        String darkNote = "";
        try {
            long sum = 0;
            int n = 0;
            for (int i = 0; i < px.length; i += 97) {
                int c = px[i];
                sum += ((c >> 16) & 0xff) + ((c >> 8) & 0xff) + (c & 0xff);
                n++;
            }
            if (n > 0 && sum / (3.0 * n) < 10.0) darkNote = "画面是全黑，录屏通道可能失效";
        } catch (Throwable ignored) {
        }
        legs.set(2);
        long t0 = System.currentTimeMillis();
        List<CardMatcher.Hit> hits = new ArrayList<>();
        String matchNote = "";
        try {
            CardLibrary lib = CardLibrary.get(this);
            if (lib.cards.isEmpty()) matchNote = "（卡图库为空）";

            /* ① 手牌：按 MD 固定版面定位每个卡位，再小范围对齐（最准） */
            int handHit = 0;
            for (SlotRecognizer.Slot s : SlotRecognizer.hand(px, w, h, lib.templates)) {
                if (!s.confident()) continue;
                hits.add(toHit(s));
                handHit++;
            }

            /* ② 场上 20 个格子：双方各 5 怪兽 + 5 魔陷，按实测版面定位 */
            int fieldHit = 0;
            for (SlotRecognizer.Slot s : SlotRecognizer.field(px, w, h, lib.templates)) {
                hits.add(toHit(s));
                fieldHit++;
            }
            matchNote = "（手牌 " + handHit + "，场上 " + fieldHit + "）";
        } catch (Throwable t) {
            Log.w(TAG, "card match failed", t);
            matchNote = "（卡图匹配失败：" + t.getClass().getSimpleName() + "）";
        }
        if (darkNote.length() > 0) matchNote = (matchNote + " " + darkNote).trim();
        long ms = System.currentTimeMillis() - t0;
        sendCards(hits, w, h, ms, matchNote);
        endLeg();
        postStatus("文字识别中（卡图 " + hits.size() + " 处，" + ms + "ms）…");
        runOcrBands(bmp, w, h);
    }

    /** 一路结果结束；两路都结束就放开 capturing。 */
    private void endLeg() {
        if (legs.decrementAndGet() <= 0) {
            legs.set(0);
            capturing = false;
        }
    }

    private void sendCards(List<CardMatcher.Hit> hits, int w, int h, long ms, String note) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < hits.size(); i++) {
            CardMatcher.Hit hit = hits.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"n\":").append(jsString(hit.name))
                    .append(",\"s\":").append(Math.round(hit.score * 100) / 100f)
                    .append(",\"x\":").append(Math.round(hit.cx))
                    .append(",\"y\":").append(Math.round(hit.cy))
                    .append(",\"w\":").append(Math.round(hit.cw))
                    .append(",\"h\":").append(Math.round(hit.ch));
            if (hit.side != null) sb.append(",\"side\":").append(jsString(hit.side));
            sb.append('}');
        }
        sb.append(']');
        final String js = "window.__onCards && window.__onCards(" + sb + "," + w + "," + h + ","
                + ms + "," + jsString(note) + ")";
        main.post(() -> eval(js));
    }

    /**
     * 方案一：把整屏切成上下两条带，按 1.5 倍左右放大后再 OCR。
     * 卡面文字在整屏里只有十几个像素高，放大后识别率明显提升。
     */
    private void runOcrBands(Bitmap bmp, int w, int h) {
        if (ocr == null) {
            postError("识别通道未就绪，请重开浮窗");
            endLeg();
            return;
        }
        final int bands = 2;
        final List<String> parts = Collections.synchronizedList(new ArrayList<String>());
        final AtomicInteger done = new AtomicInteger();
        for (int i = 0; i < bands; i++) {
            int y0 = i == 0 ? 0 : h / 2;
            int y1 = i == 0 ? h / 2 : h;
            bandOcr(bmp, 0, y0, w, y1 - y0, parts, done, bands, w, h);
        }
    }

    private void bandOcr(Bitmap src, int x0, int y0, int bw, int bh,
                         List<String> parts, AtomicInteger done, int total, int screenW, int screenH) {
        Bitmap band = null, scaled = null;
        try {
            band = Bitmap.createBitmap(src, x0, y0, bw, bh);
            float scale = Math.min(2.0f, 1600f / bw);
            if (scale < 1.05f) scale = 1.05f;
            scaled = Bitmap.createScaledBitmap(band, Math.round(bw * scale), Math.round(bh * scale), true);
            final float s = scale;
            final int oy = y0;
            final Bitmap toFree = scaled;
            InputImage input = InputImage.fromBitmap(scaled, 0);
            ocr.process(input)
                    .addOnSuccessListener(text -> {
                        parts.add(linesJson(text, s, oy));
                        recycleQuietly(toFree);
                        finishBands(parts, done, total);
                    })
                    .addOnFailureListener(e -> {
                        recycleQuietly(toFree);
                        finishBands(parts, done, total);
                    });
        } catch (Throwable t) {
            Log.w(TAG, "band ocr failed", t);
            recycleQuietly(scaled);
            finishBands(parts, done, total);
        } finally {
            if (band != null && band != scaled) recycleQuietly(band);
        }
    }

    private static void recycleQuietly(Bitmap bmp) {
        if (bmp == null) return;
        try {
            bmp.recycle();
        } catch (Throwable ignored) {
        }
    }

    private void finishBands(List<String> parts, AtomicInteger done, int total) {
        if (done.incrementAndGet() < total) return;
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        synchronized (parts) {
            for (String p : parts) {
                if (p == null || p.length() <= 2) continue;
                String inner = p.substring(1, p.length() - 1);
                if (inner.isEmpty()) continue;
                if (!first) sb.append(',');
                first = false;
                sb.append(inner);
            }
        }
        sb.append(']');
        final String js = "window.__onOcr && window.__onOcr(" + sb + ")";
        main.post(() -> eval(js));
        endLeg();
    }

    /** OCR 结果 → [{t,x,y,w,h}]，坐标换算回整屏坐标系。 */
    private String linesJson(Text text, float scale, int offsetY) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        try {
            for (Text.TextBlock block : text.getTextBlocks()) {
                for (Text.Line line : block.getLines()) {
                    Rect r = line.getBoundingBox();
                    if (r == null) continue;
                    if (!first) sb.append(',');
                    first = false;
                    sb.append("{\"t\":").append(jsString(line.getText()))
                            .append(",\"x\":").append(Math.round(r.left / scale))
                            .append(",\"y\":").append(Math.round(r.top / scale) + offsetY)
                            .append(",\"w\":").append(Math.round(r.width() / scale))
                            .append(",\"h\":").append(Math.round(r.height() / scale))
                            .append('}');
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "read lines failed", t);
        }
        sb.append(']');
        return sb.toString();
    }

    private void eval(String js) {
        if (web == null) return;
        try {
            web.evaluateJavascript(js, null);
        } catch (Throwable ignored) {
        }
    }

    /** 把当前帧存成 PNG 到「下载/DSH-Go/duelflow-shots/」（不需要存储权限）。 */
    private void savePng(Bitmap bmp) {
        String name = "duelflow-"
                + new java.text.SimpleDateFormat("MMdd-HHmmss", java.util.Locale.US).format(new java.util.Date())
                + ".png";
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                android.content.ContentValues v = new android.content.ContentValues();
                v.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name);
                v.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/png");
                v.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                        android.os.Environment.DIRECTORY_DOWNLOADS + "/DSH-Go/duelflow-shots");
                android.net.Uri uri = getContentResolver()
                        .insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) throw new IllegalStateException("insert 失败");
                java.io.OutputStream os = getContentResolver().openOutputStream(uri);
                bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
                os.close();
                postStatus("已存图：下载/DSH-Go/duelflow-shots/" + name);
                return;
            } catch (Throwable t) {
                Log.w(TAG, "save png via MediaStore failed", t);
            }
        }
        try {
            java.io.File dir = new java.io.File(getExternalFilesDir(null), "shots");
            dir.mkdirs();
            java.io.File f = new java.io.File(dir, name);
            java.io.FileOutputStream fo = new java.io.FileOutputStream(f);
            bmp.compress(Bitmap.CompressFormat.PNG, 100, fo);
            fo.close();
            postStatus("已存图：" + f.getAbsolutePath());
        } catch (Throwable t) {
            Log.w(TAG, "save png failed", t);
            postError("存图失败：" + t.getClass().getSimpleName());
        }
    }

    private static CardMatcher.Hit toHit(SlotRecognizer.Slot s) {
        CardMatcher.Hit hh = new CardMatcher.Hit();
        hh.name = s.name;
        hh.side = s.side;
        hh.score = s.score;
        hh.cx = s.x + s.w / 2f;
        hh.cy = s.y + s.h / 2f;
        hh.cw = s.w;
        hh.ch = s.h;
        return hh;
    }

    /** 阶段提示：让浮窗上一直能看到「卡在哪一步」。 */
    private void postStatus(String msg) {
        main.post(() -> eval("window.__onStatus && window.__onStatus(" + jsString(msg) + ")"));
    }

    private void postError(String msg) {
        main.post(() -> eval("window.__onOcrError && window.__onOcrError(" + jsString(msg) + ")"));
    }

    private static String jsString(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': b.append("\\\\"); break;
                case '"': b.append("\\\""); break;
                case '\n': b.append("\\n"); break;
                case '\r': break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(' '); else b.append(c);
            }
        }
        b.append('"');
        return b.toString();
    }

    /* ---------------- JS 桥 ---------------- */

    private class Bridge {
        @JavascriptInterface
        public void capture() {
            main.post(() -> captureOnce(false));
        }

        /** 识别一次，并把原始帧存到「下载/DSH-Go/duelflow-shots/」，方便离线调阈值。 */
        @JavascriptInterface
        public void snap() {
            main.post(() -> captureOnce(true));
        }

        @JavascriptInterface
        public void close() {
            main.post(() -> stopSelf());
        }

        @JavascriptInterface
        public void focus(boolean on) {
            main.post(() -> setFocusable(on));
        }

        /** 把最后一次识别的原始数据放进剪贴板，方便反馈问题时发给 AI。 */
        @JavascriptInterface
        public void copy(String text) {
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("DuelFlow", text == null ? "" : text));
                }
            } catch (Throwable t) {
                Log.w(TAG, "copy failed", t);
            }
        }

        @JavascriptInterface
        public void toast(String msg) {
            /* 浮窗内部用 WebView 自己的提示即可，这里只记日志 */
            Log.i(TAG, "overlay: " + msg);
        }
    }

    /* ---------------- 生命周期 ---------------- */

    private void cleanup() {
        if (web != null) {
            try {
                web.loadUrl("about:blank");
                web.destroy();
            } catch (Throwable ignored) {
            }
            web = null;
        }
        if (root != null && wm != null) {
            try {
                wm.removeView(root);
            } catch (Throwable ignored) {
            }
        }
        root = null;
        if (reader != null) {
            try {
                reader.close();
            } catch (Throwable ignored) {
            }
            reader = null;
        }
        if (display != null) {
            try {
                display.release();
            } catch (Throwable ignored) {
            }
            display = null;
        }
        if (projection != null) {
            try {
                projection.stop();
            } catch (Throwable ignored) {
            }
            projection = null;
        }
        if (ocr != null) {
            try {
                ocr.close();
            } catch (Throwable ignored) {
            }
            ocr = null;
        }
    }

    @Override
    public void onDestroy() {
        cleanup();
        try {
            worker.shutdownNow();
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }
}
