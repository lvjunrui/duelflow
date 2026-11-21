package com.duelflow.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * 极简 WebView 壳：主界面逻辑在 assets/index.html，完全离线。
 * 额外提供「小浮窗」入口：悬浮窗 + 屏幕识别 + 决策提示（OverlayService）。
 */
public class MainActivity extends Activity {

    private static final int REQ_OVERLAY_PERM = 101;
    private static final int REQ_CAPTURE = 102;
    private static final int REQ_NOTIF = 103;
    private static final int REQ_PICK_JSON = 104;

    private WebView web;
    private MediaProjectionManager projectionManager;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setTextZoom(100);

        web.setBackgroundColor(0xFF0E1116);
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new AndroidBridge(), "Android");
        web.loadUrl("file:///android_asset/index.html");

        setContentView(web);
    }

    /* ---------------- 浮窗入口 ---------------- */

    private class AndroidBridge {
        @JavascriptInterface
        public void startOverlay() {
            runOnUiThread(MainActivity.this::beginOverlay);
        }

        @JavascriptInterface
        public void stopOverlay() {
            runOnUiThread(() -> stopService(new Intent(MainActivity.this, OverlayService.class)));
        }

        @JavascriptInterface
        public boolean canOverlay() {
            return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(MainActivity.this);
        }

        @JavascriptInterface
        public void pickJson() {
            runOnUiThread(MainActivity.this::openJsonPicker);
        }

        @JavascriptInterface
        public void toast(String msg) {
            android.widget.Toast.makeText(MainActivity.this, msg, android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private void beginOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(i, REQ_OVERLAY_PERM);
            } catch (Throwable t) {
                toast("无法打开悬浮窗权限页，请到系统设置手动允许");
            }
            return;
        }
        askNotification();
        requestCapture();
    }

    private void askNotification() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
    }

    private void requestCapture() {
        if (projectionManager == null) {
            toast("系统不支持屏幕投屏");
            return;
        }
        try {
            startActivityForResult(projectionManager.createScreenCaptureIntent(), REQ_CAPTURE);
        } catch (Throwable t) {
            toast("无法申请屏幕录制权限");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_OVERLAY_PERM) {
            if (Settings.canDrawOverlays(this)) {
                askNotification();
                requestCapture();
            } else {
                toast("没有悬浮窗权限，浮窗无法显示");
            }
            return;
        }
        if (requestCode == REQ_PICK_JSON) {
            if (resultCode == RESULT_OK) readPickedJson(data);
            return;
        }
        if (requestCode == REQ_CAPTURE) {
            if (resultCode != RESULT_OK || data == null) {
                toast("已取消屏幕识别授权");
                return;
            }
            Intent svc = new Intent(this, OverlayService.class);
            svc.setAction(OverlayService.ACTION_START);
            svc.putExtra(OverlayService.EXTRA_CODE, resultCode);
            svc.putExtra(OverlayService.EXTRA_DATA, data);
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(svc);
                } else {
                    startService(svc);
                }
            } catch (Throwable t) {
                toast("浮窗启动失败：" + t.getClass().getSimpleName());
            }
        }
    }

    private void openJsonPicker() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, REQ_PICK_JSON);
        } catch (Throwable t) {
            toast("打不开文件选择器");
        }
    }

    private void readPickedJson(Intent data) {
        if (data == null || data.getData() == null) return;
        String text = null;
        try {
            java.io.InputStream in = getContentResolver().openInputStream(data.getData());
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            text = new String(bo.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            web.post(() -> web.evaluateJavascript(
                    "window.__onPickFail && window.__onPickFail('读取文件失败')", null));
            return;
        }
        if (text == null) return;
        final String js = "window.__onJsonPicked && window.__onJsonPicked(" + jsString(text) + ")";
        web.post(() -> web.evaluateJavascript(js, null));
    }

    static String jsString(String s) {
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
        return b.append('"').toString();
    }

    private void toast(String msg) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript(
                "(window.__onAndroidBack && window.__onAndroidBack()) ? 'true' : 'false'",
                value -> {
                    if (!"\"true\"".equals(value)) {
                        MainActivity.super.onBackPressed();
                    }
                });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }
}
