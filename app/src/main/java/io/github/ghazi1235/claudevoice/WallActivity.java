package io.github.ghazi1235.claudevoice;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

/**
 * Full-screen camera wall. Hosts a WebView that loads the bundled 18-camera grid
 * (assets/wall-snap.html), which talks to a go2rtc server on the center PC. The PC's address is
 * read from the shared "cam_host" preference (default is the center PC's Tailscale IP) and handed
 * to the page as ?host=, so the same page works whether the PC is on the LAN or reached over
 * Tailscale.
 *
 * Opened from MainActivity's on-screen menu (Cameras) or its RED key; BACK finishes it and returns
 * to the voice screen. Purely a viewing surface — no voice, no recognition, no API key here.
 *
 * Reliability: the WebView is rebuilt in {@link #createWeb()} so a dead render process can be
 * recovered (see {@link WallWebViewClient#onRenderProcessGone}); onPause/onResume also drive the
 * page's own pauseWall()/resumeWall() so a backgrounded wall stops polling cameras and releases the
 * live decoder instead of leaving the WebView to time its timers out on its own.
 */
public class WallActivity extends Activity {

    private static final String PREFS = "claude_voice";
    private static final String KEY_CAM_HOST = "cam_host";
    /** The center PC's Tailscale IP. Package-visible so MainActivity's status panel can show it. */
    static final String DEFAULT_CAM_HOST = "100.80.48.88";
    private static final int BG = 0xFF05010E;  // @color/bg, so no white flash before the page paints
    /** Cap on render-process recreations, so a WebView that keeps crashing can't loop forever. */
    private static final int MAX_RENDERER_RESTARTS = 3;

    private FrameLayout root;
    private WebView web;
    private int rendererRestarts = 0;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        setContentView(root);
        applyImmersive();   // edge-to-edge: hide the TV's system bars so the wall fills the whole panel

        // The address usually arrives via MainActivity (--es cam_host ...), which stores it in the
        // shared prefs; accept it here too so WallActivity can be launched directly over adb.
        storeCamHostFrom(getIntent());
        createWeb();
    }

    /**
     * Immersive full-screen: hide the status and navigation bars and draw under them, so the wall
     * uses the entire display. Sticky so a stray D-pad event doesn't permanently reveal the bars.
     * (TV overscan — the panel cropping the edges — is a separate TV picture setting, not this.)
     */
    private void applyImmersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
              | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
              | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
              | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
              | View.SYSTEM_UI_FLAG_FULLSCREEN
              | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            applyImmersive();   // re-assert immersive after focus returns (dialogs, resume, etc.)
        }
    }

    /**
     * Build (or rebuild) the WebView, attach it to the root, apply settings, and load the wall. Kept
     * separate from onCreate so {@link WallWebViewClient#onRenderProcessGone} can replace a crashed
     * render process with a fresh WebView without recreating the whole activity.
     */
    private void createWeb() {
        web = new WebView(this);
        web.setBackgroundColor(BG);
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        // The tiles are iframes to go2rtc; let them run without a user tap and cache sessions.
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        web.setWebViewClient(new WallWebViewClient());  // keep navigation inside the WebView + recover crashes
        web.setWebChromeClient(new WebChromeClient());  // lets the page's media play
        // The page drives the overview / spotlight layouts from the D-pad and OK, so the key events
        // must reach the WebView rather than stop at the activity.
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        web.requestFocus();

        String camHost = prefs().getString(KEY_CAM_HOST, DEFAULT_CAM_HOST);
        web.loadUrl("file:///android_asset/wall-snap.html?host=" + Uri.encode(camHost));
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private void storeCamHostFrom(Intent intent) {
        if (intent == null) {
            return;
        }
        String host = intent.getStringExtra(KEY_CAM_HOST);
        if (TextUtils.isEmpty(host)) {
            return;
        }
        prefs().edit().putString(KEY_CAM_HOST, host.trim()).apply();
    }

    /** Fire a page function if the WebView is alive; never throws into the caller. */
    private void callPage(String js) {
        if (web == null) {
            return;
        }
        try {
            web.evaluateJavascript("try{" + js + "}catch(e){}", null);
        } catch (Exception ignored) {
            // A WebView whose render process just died can throw here; the next recovery handles it.
        }
    }

    /**
     * The D-pad and OK drive the page's overview / spotlight / full-screen layouts. They are handed
     * to the page's own functions here, ahead of the WebView, so the wall reacts the same way whatever
     * the WebView's focus or key mapping happens to be. Everything else goes through untouched.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        String call = null;
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:   call = "wallMove(-1,0)"; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:  call = "wallMove(1,0)";  break;
            case KeyEvent.KEYCODE_DPAD_UP:     call = "wallMove(0,-1)"; break;
            case KeyEvent.KEYCODE_DPAD_DOWN:   call = "wallMove(0,1)";  break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER: call = "wallEnter()";   break;
            default:
                return super.dispatchKeyEvent(event);
        }
        if (web != null && event.getAction() == KeyEvent.ACTION_DOWN) {
            web.evaluateJavascript("try{" + call + "}catch(e){}", null);
        }
        return true;   // swallow the matching UP as well, so the WebView never sees half a key press
    }

    /**
     * BACK steps the wall out one level first (full screen -> spotlight -> overview); only when the
     * page reports there is nothing left to step out of does it leave the wall and return to the
     * voice screen. It never navigates the WebView.
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (web == null) {
                finish();
                return true;
            }
            web.evaluateJavascript(
                    "(function(){try{return !!(window.wallBack&&wallBack());}catch(e){return false;}})()",
                    handled -> {
                        if (!"true".equals(handled) && !isFinishing()) {
                            finish();
                        }
                    });
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onPause() {
        // Tell the page to stop its own work (scheduler, tour, live decoder) before the WebView is
        // paused, so a backgrounded wall isn't polling cameras or holding a decoder.
        callPage("window.pauseWall&&pauseWall()");
        if (web != null) {
            web.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) {
            web.onResume();
        }
        // Restart exactly one scheduler generation (or reload the full-screen player). Idempotent.
        callPage("window.resumeWall&&resumeWall()");
    }

    @Override
    protected void onDestroy() {
        destroyWeb();
        super.onDestroy();
    }

    /** Detach and destroy the current WebView, if any. */
    private void destroyWeb() {
        if (web == null) {
            return;
        }
        ViewGroup parent = (ViewGroup) web.getParent();
        if (parent != null) {
            parent.removeView(web);
        }
        web.stopLoading();
        web.loadUrl("about:blank");
        web.destroy();
        web = null;
    }

    /**
     * Keeps navigation inside the WebView and, critically, recovers from a dead render process. On
     * Android 9 (and any API 26+, which is this app's minimum) the system can kill a WebView's render
     * process under memory pressure or on a decoder crash; the default client would leave a blank,
     * unusable view. Here the dead WebView is destroyed and a fresh one is built, bounded so a
     * repeatedly crashing page returns to the voice screen instead of looping.
     */
    private class WallWebViewClient extends WebViewClient {
        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            if (view != web) {
                // Not the live WebView (e.g. an already-replaced one); let it go.
                if (view != null) {
                    view.destroy();
                }
                return true;
            }
            destroyWeb();   // the crashed WebView can never be reused; it must be destroyed
            if (rendererRestarts < MAX_RENDERER_RESTARTS && !isFinishing()) {
                rendererRestarts++;
                createWeb();
            } else if (!isFinishing()) {
                finish();   // give up after repeated crashes and return to the voice screen
            }
            return true;    // handled: don't let the system kill the activity/process
        }
    }
}
