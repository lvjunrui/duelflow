package com.duelflow.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * 极简 WebView 壳：整个应用逻辑在 assets/index.html 里，完全离线。
 */
public class MainActivity extends Activity {

    private WebView web;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

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
        web.loadUrl("file:///android_asset/index.html");

        setContentView(web);
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
}
