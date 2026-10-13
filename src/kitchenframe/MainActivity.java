package kitchenframe;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;

/**
 * Kitchen photo frame: Home Assistant on touch, Immich slideshow when idle.
 *
 * Two WebViews stay loaded side by side. Home Assistant is the expensive page on this
 * RK3126 - its first load takes minutes - so it is loaded once and only hidden and paused
 * while the slideshow is up, never reloaded. The touch that dismisses the slideshow is
 * swallowed, so it cannot press whatever control sits underneath.
 *
 * Chrome remote debugging of the two pages is off unless a marker file exists in the app's
 * private storage (checked at start-up). Every app on this frame can get root, so the debug socket
 * should only exist while someone is using it:
 *   adb shell su -c 'touch /data/data/net.frameo.frame/files/webview-debug'   (then restart the app)
 */
public class MainActivity extends Activity {
  static final String TAG = "KitchenFrame";
  // Lightweight control page - the full HA frontend is too heavy for this chip. It lives in
  // Home Assistant's www folder (deployed from this project's control.html by deploy.sh), so
  // changing the buttons needs no rebuild. It links to the full dashboard; going idle, or the
  // "Controls" button, always returns here.
  static final String HA_URL = Config.CONTROL_URL;   // from config.properties

  /**
   * HA serves /local with Cache-Control max-age of 31 days, so a plain load keeps running an old
   * control.html after deploy.sh. A fresh query string on every load defeats the cache; the
   * origin is unchanged, so the sign-in in localStorage is kept.
   */
  static String controlUrl() { return HA_URL + "?t=" + System.currentTimeMillis(); }

  static boolean isControlPage(String url) { return url != null && url.startsWith(HA_URL); }

  // control.html is deployed separately (deploy.sh). The running page only changes when it is
  // reloaded, so refresh it when the frame goes idle if it is older than this.
  static final long CONTROL_MAX_AGE_MS = 30 * 60 * 1000;
  private long controlLoadedAt;

  private void loadControl() {
    controlLoadedAt = System.currentTimeMillis();
    ha.loadUrl(controlUrl());
  }
  static final String SLIDESHOW_URL = Config.SLIDESHOW_URL;   // from config.properties
  // The slideshow is the frame's normal state: it starts on it, and a touch shows the controls
  // for this long before it goes back.
  static final long IDLE_MS = 30 * 1000;
  static final long RETRY_MS = 15 * 1000;

  private WebView ha;
  private WebView slideshow;
  private Button backButton;
  private SplashView splash;
  private android.widget.TextView alertBadge;
  private boolean slideshowFailed;
  private final java.util.Map<WebView, Runnable> pendingRetries = new java.util.HashMap<WebView, Runnable>();
  private boolean slideshowShowing;
  private boolean swallowGesture;
  private boolean slideshowLoaded;

  // Night mode: the control page tells us (via the KitchenFrame JS bridge) when the kitchen light
  // is off and the sun is down. Then the screen shows a dim full-screen clock with the backlight
  // turned well down; a touch shows the controls for IDLE_MS, and the kitchen light coming on
  // wakes it to the slideshow.
  static final String BACKLIGHT = "/sys/class/backlight/rk28_bl/brightness";
  static final int NIGHT_BACKLIGHT = 12;     // of 255; 0 is fully off and hides the clock
  static final int NIGHT_TEXT = 0xFF6E6E6E;  // dim grey
  static final int DRIFT_PX = 24;            // the clock wanders this far, once a minute
  private FrameLayout nightCover;
  private android.widget.LinearLayout nightClock;
  private android.widget.TextView clockTime;
  private android.widget.TextView clockDate;
  private final java.util.Random drift = new java.util.Random();

  private final Runnable tickClock = new Runnable() {
    @Override public void run() {
      java.util.Date now = new java.util.Date();
      clockTime.setText(new java.text.SimpleDateFormat("h:mm", java.util.Locale.US).format(now));
      clockDate.setText(new java.text.SimpleDateFormat("EEEE, MMMM d", java.util.Locale.US).format(now));
      nightClock.setTranslationX(drift.nextInt(2 * DRIFT_PX + 1) - DRIFT_PX);
      nightClock.setTranslationY(drift.nextInt(2 * DRIFT_PX + 1) - DRIFT_PX);
      // Next tick just after the minute turns over.
      long ms = System.currentTimeMillis();
      handler.postDelayed(this, 60000 - (ms % 60000) + 200);
    }
  };
  private boolean night;        // what Home Assistant says
  private boolean nightActive;  // whether the screen is currently blacked out

  private final Handler handler = new Handler(Looper.getMainLooper());

  private final Runnable goIdle = new Runnable() {
    @Override public void run() {
      if (night) enterNight(); else showSlideshow();
      tidyLeftoverApps();
      if (System.currentTimeMillis() - controlLoadedAt > CONTROL_MAX_AGE_MS) {
        Log.i(TAG, "Idle: refreshing control page");
        loadControl();
      }
    }
  };

  // True only while the kiosk is the activity on screen. The idle timer only counts touches on the
  // kiosk, so this guard is what stops an app you are using elsewhere from being closed.
  private boolean resumed;

  @Override protected void onResume() { super.onResume(); resumed = true; }
  @Override protected void onPause() { resumed = false; super.onPause(); }

  /**
   * When the frame settles back to the slideshow or night clock, close anything left running
   * from a trip into Settings or another app - the frame has 1 GB of RAM and no way to see
   * what's open except the edge menu's Running apps page.
   */
  private void tidyLeftoverApps() {
    if (!resumed) return;
    new Thread(new Runnable() {
      @Override public void run() {
        java.util.List<String> left = RunningApps.list(MainActivity.this);
        if (!left.isEmpty()) {
          Log.i(TAG, "Idle: tidying leftover apps");
          RunningApps.close(left);
        }
      }
    }).start();
  }

  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);
    enableWebDebuggingIfMarked();
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        | WindowManager.LayoutParams.FLAG_FULLSCREEN);

    FrameLayout root = new FrameLayout(this);
    root.setBackgroundColor(Color.BLACK);
    ha = makeWebView();
    slideshow = makeWebView();
    slideshow.setVisibility(View.GONE);
    root.addView(ha);
    root.addView(slideshow);

    // The frame has no working Back button, so the full dashboard gets one of its own.
    backButton = new Button(this);
    backButton.setText("‹ Controls");
    backButton.setTextSize(18);
    backButton.setTextColor(Color.WHITE);
    backButton.setBackgroundColor(0xCC2D5A8C);
    backButton.setVisibility(View.GONE);
    backButton.setOnClickListener(new View.OnClickListener() {
      @Override public void onClick(View v) { loadControl(); }
    });
    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
        Gravity.BOTTOM | Gravity.START);
    lp.setMargins(16, 16, 16, 16);
    root.addView(backButton, lp);

    nightCover = new FrameLayout(this);
    nightCover.setBackgroundColor(Color.BLACK);
    nightCover.setVisibility(View.GONE);
    nightClock = new android.widget.LinearLayout(this);
    nightClock.setOrientation(android.widget.LinearLayout.VERTICAL);
    nightClock.setGravity(Gravity.CENTER_HORIZONTAL);
    clockTime = new android.widget.TextView(this);
    clockTime.setTextColor(NIGHT_TEXT);
    clockTime.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 440);
    clockTime.setTypeface(android.graphics.Typeface.create("sans-serif-thin", android.graphics.Typeface.NORMAL));
    clockDate = new android.widget.TextView(this);
    clockDate.setTextColor(NIGHT_TEXT);
    clockDate.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 56);
    nightClock.addView(clockTime);
    nightClock.addView(clockDate);
    nightCover.addView(nightClock, new FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
    root.addView(nightCover);

    // A small warning that sits over the slideshow and the night clock when the control page
    // can't do its job - not signed in, or Home Assistant unreachable. Night mode and the
    // light buttons fail silently otherwise (found 2026-10-10 after the package rename).
    alertBadge = new android.widget.TextView(this);
    alertBadge.setTextColor(0xFFF2A541);
    alertBadge.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16);
    alertBadge.setBackgroundColor(0xB0000000);
    alertBadge.setPadding(14, 8, 14, 8);
    alertBadge.setVisibility(View.GONE);
    FrameLayout.LayoutParams al = new FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
        Gravity.BOTTOM | Gravity.END);
    al.setMargins(16, 16, 16, 16);
    root.addView(alertBadge, al);

    // Carries on from the boot animation until the slideshow has actually loaded.
    splash = new SplashView(this);
    root.addView(splash);
    splash.setStatus(isOnline() ? "Loading slideshow…" : "Connecting to Wi-Fi…");

    ensureEdgeMenu();
    // The backlight is hardware state: it keeps whatever the last process set. If the app was
    // restarted during night mode it would otherwise stay dimmed, because a fresh start assumes
    // day and sees nothing to change. Start every run at the normal level.
    setBacklight(android.provider.Settings.System.getInt(getContentResolver(),
        android.provider.Settings.System.SCREEN_BRIGHTNESS, 178));

    ha.addJavascriptInterface(new NightBridge(), "KitchenFrame");
    setContentView(root);

    // Load the controls behind the slideshow so they are already signed in and live by the
    // time anyone touches the screen.
    watchNetwork();
    loadControl();
    showSlideshow();
  }

  private WebView makeWebView() {
    final WebView w = new WebView(this);
    w.setBackgroundColor(Color.BLACK);
    WebSettings s = w.getSettings();
    s.setJavaScriptEnabled(true);
    s.setDomStorageEnabled(true);   // Home Assistant keeps its login token in localStorage
    s.setUseWideViewPort(true);
    s.setLoadWithOverviewMode(true);
    s.setMediaPlaybackRequiresUserGesture(false);
    w.setWebViewClient(new WebViewClient() {
      @Override
      public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
        if (view == slideshow) slideshowFailed = false;
        if (view == ha && backButton != null) {
          backButton.setVisibility(isControlPage(url) ? View.GONE : View.VISIBLE);
        }
      }

      @Override
      public void onPageFinished(WebView view, String url) {
        // Give the first photo a moment to fade in before the splash goes.
        if (view == slideshow && !slideshowFailed && splash != null && splash.isShowing()) {
          handler.postDelayed(hideSplash, 1500);
        }
      }

      // Wi-Fi on this frame drops for a few seconds now and then; a page that failed to
      // load during one of those blips is retried instead of sitting on an error forever.
      @Override
      public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
        if (req.isForMainFrame()) scheduleRetry(view, req.getUrl().toString(), err.getDescription());
      }

      @SuppressWarnings("deprecation")
      @Override
      public void onReceivedError(WebView view, int code, String desc, String failingUrl) {
        scheduleRetry(view, failingUrl, desc);
      }
    });
    return w;
  }

  private final Runnable hideSplash = new Runnable() {
    @Override public void run() { splash.dismiss(); }
  };

  private boolean isOnline() {
    android.net.NetworkInfo n =
        ((ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE)).getActiveNetworkInfo();
    return n != null && n.isConnected();
  }

  private void scheduleRetry(final WebView view, final String url, CharSequence why) {
    if (view == slideshow) {
      slideshowFailed = true;
      if (splash != null && splash.isShowing()) {
        splash.setStatus(isOnline() ? "Waiting for the photo server…" : "Connecting to Wi-Fi…");
      }
    }
    Log.w(TAG, "Load failed (" + why + "), retrying " + url + " when Wi-Fi is up, or in "
        + RETRY_MS / 1000 + " s");
    Runnable retry = new Runnable() {
      @Override public void run() {
        pendingRetries.remove(view);
        view.loadUrl(url);
      }
    };
    Runnable old = pendingRetries.put(view, retry);
    if (old != null) handler.removeCallbacks(old);
    handler.postDelayed(retry, RETRY_MS);
  }

  /**
   * At boot the app is up before Wi-Fi is, so the first loads fail. Instead of waiting out the
   * 15 s timer, retry the moment a network comes up. The timer stays as a fallback.
   */
  private void watchNetwork() {
    ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
    cm.registerNetworkCallback(new NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
        new ConnectivityManager.NetworkCallback() {
          @Override public void onAvailable(Network network) {
            handler.post(new Runnable() {
              @Override public void run() {
                if (splash != null && splash.isShowing()) splash.setStatus("Loading slideshow…");
                if (pendingRetries.isEmpty()) return;
                Log.i(TAG, "Network up: retrying " + pendingRetries.size() + " failed page(s) now");
                for (Runnable r : new java.util.ArrayList<Runnable>(pendingRetries.values())) {
                  handler.removeCallbacks(r);
                  r.run();
                }
              }
            });
          }
        });
  }

  /** Called from control.html. Only setNight and setAlert are exposed to the page. */
  private class NightBridge {
    @android.webkit.JavascriptInterface
    public void setAlert(final String text) {
      handler.post(new Runnable() {
        @Override public void run() {
          boolean show = text != null && text.length() > 0;
          if (show) alertBadge.setText(text);
          alertBadge.setVisibility(show ? View.VISIBLE : View.GONE);
          Log.i(TAG, show ? "Alert: " + text : "Alert cleared");
        }
      });
    }

    @android.webkit.JavascriptInterface
    public void setNight(final boolean on) {
      handler.post(new Runnable() {
        @Override public void run() {
          if (on == night) return;
          night = on;
          Log.i(TAG, "Night mode " + (on ? "on (kitchen light off, sun down)" : "off"));
          if (on) {
            if (!slideshowShowing && !nightActive) return; // someone is using the controls
            enterNight();
          } else if (nightActive) {
            exitNight();
            showSlideshow();
          }
        }
      });
    }
  }

  private void enterNight() {
    if (nightActive) return;
    nightActive = true;
    handler.removeCallbacks(tickClock);
    tickClock.run();
    nightCover.setVisibility(View.VISIBLE);
    slideshow.onPause();
    setBacklight(NIGHT_BACKLIGHT);
  }

  private void exitNight() {
    if (!nightActive) return;
    nightActive = false;
    handler.removeCallbacks(tickClock);
    nightCover.setVisibility(View.GONE);
    // enterNight() paused the slideshow. Always resume it here: the kitchen-light-on path goes
    // straight to showSlideshow(), which returns early because slideshowShowing is still true -
    // so without this the slideshow stayed frozen on the photo (and clock) from when night began.
    slideshow.onResume();
    int level = android.provider.Settings.System.getInt(getContentResolver(),
        android.provider.Settings.System.SCREEN_BRIGHTNESS, 178);
    setBacklight(level);
  }

  /**
   * Android drops an accessibility service from the enabled list when its app is force-stopped,
   * and never puts it back - which silently killed the edge swipe once. Re-enable it on every
   * start if it's missing. Writing secure settings needs root; every app on this frame has it.
   */
  private void ensureEdgeMenu() {
    // Fully qualified: the app id is net.frameo.frame but the class lives in kitchenframe.
    final String svc = getPackageName() + "/" + EdgeMenuService.class.getName();
    String on = android.provider.Settings.Secure.getString(getContentResolver(),
        android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
    if (on != null && on.contains(svc)) return;
    Log.w(TAG, "Edge menu was switched off - re-enabling it");
    new Thread(new Runnable() {
      @Override public void run() {
        try {
          Runtime.getRuntime().exec(new String[] { "su", "-c",
              "settings put secure enabled_accessibility_services " + svc
                  + "; settings put secure accessibility_enabled 1" }).waitFor();
        } catch (Exception e) {
          Log.w(TAG, "Could not re-enable the edge menu", e);
        }
      }
    }).start();
  }

  /** The backlight is a sysfs file only root may write; every app on this frame gets root. */
  private void setBacklight(final int level) {
    new Thread(new Runnable() {
      @Override public void run() {
        try {
          Runtime.getRuntime().exec(new String[] {
              "su", "-c", "echo " + level + " > " + BACKLIGHT }).waitFor();
        } catch (Exception e) {
          Log.w(TAG, "Could not set backlight to " + level, e);
        }
      }
    }).start();
  }

  private void showSlideshow() {
    if (slideshowShowing) return;
    Log.i(TAG, "Idle: showing slideshow");
    slideshowShowing = true;
    if (!slideshowLoaded) {
      slideshow.loadUrl(SLIDESHOW_URL);
      slideshowLoaded = true;
    }
    slideshow.onResume();
    slideshow.setVisibility(View.VISIBLE);
    // If someone opened the full dashboard, drop back to the light page while nobody's looking.
    if (!isControlPage(ha.getUrl())) loadControl();
    // The control page is NOT paused: it must keep hearing Home Assistant to know when night
    // starts and when the kitchen light comes on. It costs ~5 % CPU.
  }

  private void showHomeAssistant() {
    Log.i(TAG, "Touch: showing Home Assistant");
    slideshowShowing = false;
    ha.onResume();
    slideshow.setVisibility(View.GONE);
    slideshow.onPause();
  }

  private void resetIdleTimer() {
    handler.removeCallbacks(goIdle);
    handler.postDelayed(goIdle, IDLE_MS);
  }

  @Override
  public boolean dispatchTouchEvent(MotionEvent e) {
    resetIdleTimer();

    int action = e.getActionMasked();
    if (action == MotionEvent.ACTION_DOWN && splash != null && splash.isShowing()) {
      handler.removeCallbacks(hideSplash);
      splash.dismiss(); // a touch skips the splash; the code below then shows the controls
    }
    if (nightActive && action == MotionEvent.ACTION_DOWN) {
      exitNight();
      showHomeAssistant(); // at night a touch means "I want a light", so go to the controls
      swallowGesture = true;
    } else if (slideshowShowing && action == MotionEvent.ACTION_DOWN) {
      // Wait for the finger to lift: a tap shows the controls, a sideways swipe flips photos.
      slideshowGesture = true;
      downX = e.getX();
      downY = e.getY();
      swallowGesture = true;
    }
    if (slideshowGesture && (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)) {
      slideshowGesture = false;
      float dx = e.getX() - downX, dy = e.getY() - downY;
      if (action == MotionEvent.ACTION_UP && Math.abs(dx) > SWIPE_PX && Math.abs(dx) > 1.5f * Math.abs(dy)) {
        flipPhoto(dx > 0); // swipe right = back to the previous photo, like a phone gallery
      } else if (action == MotionEvent.ACTION_UP) {
        showHomeAssistant();
      }
    }
    if (swallowGesture) {
      if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) swallowGesture = false;
      return true;
    }
    return super.dispatchTouchEvent(e);
  }

  static final float SWIPE_PX = 100;
  private boolean slideshowGesture;
  private float downX, downY;

  /**
   * Immich Kiosk has previous/next built in (it keeps a history), but only as buttons in its own
   * menu - no swipe handling. So a swipe here clicks those buttons in the page. They exist in the
   * DOM even while Kiosk's menu is hidden.
   */
  private void flipPhoto(boolean previous) {
    final String which = previous ? "prev" : "next";
    slideshow.evaluateJavascript(
        "(function(){var b=document.querySelector('.navigation--" + which + "-asset');"
            + "if(!b)return 'missing';b.classList.remove('disabled');b.click();return 'ok';})()",
        new android.webkit.ValueCallback<String>() {
          @Override public void onReceiveValue(String r) {
            Log.i(TAG, "Swipe: " + which + " photo -> " + r);
          }
        });
  }

  private void enableWebDebuggingIfMarked() {
    boolean on = new java.io.File(getFilesDir(), "webview-debug").exists();
    WebView.setWebContentsDebuggingEnabled(on);
    if (on) Log.w(TAG, "Chrome remote debugging enabled by marker file");
  }

  @Override
  public void onWindowFocusChanged(boolean hasFocus) {
    super.onWindowFocusChanged(hasFocus);
    if (hasFocus) {
      getWindow().getDecorView().setSystemUiVisibility(
          View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
              | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
              | View.SYSTEM_UI_FLAG_FULLSCREEN
              | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
              | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
              | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }
  }

  @Override
  public void onBackPressed() {
    if (!slideshowShowing && ha.canGoBack()) ha.goBack();
  }
}
