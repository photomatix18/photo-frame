package kitchenframe;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Swipe in from the left edge, anywhere, for a menu: Back, the frame, Settings, Wi-Fi, apps.
 *
 * This frame has no working Back or Home button, so leaving the kiosk used to mean getting
 * stuck. An accessibility service may draw over every app and press Back for you, without
 * touching Android's system files. Enabled once over adb:
 *   settings put secure enabled_accessibility_services net.frameo.frame/kitchenframe.EdgeMenuService
 *   settings put secure accessibility_enabled 1
 */
public class EdgeMenuService extends AccessibilityService {
  static final int EDGE_DP = 40;      // width of the invisible strip down the left edge (14 was too
                                      // narrow to hit with a real finger behind the bezel)
  static final int SWIPE_DP = 30;     // how far a finger must travel right to open the menu

  private WindowManager wm;
  private View edge;
  private View menu;
  private float downX;

  @Override
  protected void onServiceConnected() {
    AccessibilityServiceInfo info = new AccessibilityServiceInfo();
    info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
    info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
    setServiceInfo(info);

    wm = (WindowManager) getSystemService(WINDOW_SERVICE);
    edge = new View(this);
    edge.setOnTouchListener(new View.OnTouchListener() {
      @Override public boolean onTouch(View v, MotionEvent e) {
        switch (e.getActionMasked()) {
          case MotionEvent.ACTION_DOWN:
            downX = e.getRawX();
            return true;
          case MotionEvent.ACTION_MOVE:
            if (e.getRawX() - downX > dp(SWIPE_DP)) showMenu();
            return true;
          default:
            return true;
        }
      }
    });
    WindowManager.LayoutParams lp = overlayParams(dp(EDGE_DP), WindowManager.LayoutParams.MATCH_PARENT);
    lp.gravity = Gravity.START | Gravity.TOP;
    wm.addView(edge, lp);
    Log.i(MainActivity.TAG, "Edge menu ready");
  }

  private WindowManager.LayoutParams overlayParams(int w, int h) {
    return new WindowManager.LayoutParams(w, h,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT);
  }

  private void showMenu() {
    if (menu != null) return;
    // Full-screen dimmed backdrop: a tap outside the panel closes the menu.
    FrameLayout backdrop = new FrameLayout(this);
    backdrop.setBackgroundColor(0x99000000);
    backdrop.setOnClickListener(new View.OnClickListener() {
      @Override public void onClick(View v) { hideMenu(); }
    });

    LinearLayout panel = new LinearLayout(this);
    panel.setOrientation(LinearLayout.VERTICAL);
    panel.setBackgroundColor(0xFF1F1F1F);
    int pad = dp(16);
    panel.setPadding(pad, pad, pad, pad);
    panel.setClickable(true); // taps on the panel itself don't fall through to the backdrop

    TextView title = new TextView(this);
    title.setText("Kitchen Frame");
    title.setTextColor(0xFF888888);
    title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
    title.setPadding(0, 0, 0, dp(10));
    panel.addView(title);

    addButton(panel, "‹  Back", new Runnable() {
      @Override public void run() { performGlobalAction(GLOBAL_ACTION_BACK); }
    });
    addButton(panel, "Kitchen Frame", new Runnable() {
      @Override public void run() {
        launch(new Intent(EdgeMenuService.this, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
      }
    });
    addButton(panel, "Settings", new Runnable() {
      @Override public void run() { launch(new Intent(Settings.ACTION_SETTINGS)); }
    });
    addButton(panel, "Wi-Fi", new Runnable() {
      @Override public void run() { launch(new Intent(Settings.ACTION_WIFI_SETTINGS)); }
    });
    addButton(panel, "All apps", new Runnable() {
      @Override public void run() { openLauncher3(); }
    });
    addButton(panel, "Running apps", new Runnable() {
      @Override public void run() { showRunningApps(); }
    });
    addButton(panel, "Close", null);

    backdrop.addView(panel, new FrameLayout.LayoutParams(dp(300),
        FrameLayout.LayoutParams.MATCH_PARENT, Gravity.START));
    menu = backdrop;
    wm.addView(menu, overlayParams(WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT));
  }

  private void addButton(LinearLayout panel, String label, final Runnable action) {
    Button b = new Button(this);
    b.setText(label);
    b.setAllCaps(false);
    b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
    b.setTextColor(Color.WHITE);
    b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
    b.setBackground(rounded(0xFF333333, 14));
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, dp(64));
    lp.setMargins(0, 0, 0, dp(10));
    b.setOnClickListener(new View.OnClickListener() {
      @Override public void onClick(View v) {
        hideMenu();
        if (action != null) action.run();
      }
    });
    panel.addView(b, lp);
  }

  /**
   * Our recents page - the firmware's own recent-apps screen doesn't work. Lists apps left running
   * (see RunningApps) as cards with their icons: tap a card to switch to the app, Close to stop it,
   * Close all to stop everything and go back to the frame.
   */
  private void showRunningApps() {
    new Thread(new Runnable() {
      @Override public void run() {
        final java.util.List<String> pkgs = RunningApps.list(EdgeMenuService.this);
        new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
          @Override public void run() { buildRunningPanel(pkgs); }
        });
      }
    }).start();
  }

  private android.graphics.drawable.GradientDrawable rounded(int color, int radiusDp) {
    android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(radiusDp));
    return d;
  }

  private void buildRunningPanel(final java.util.List<String> pkgs) {
    hideMenu();
    FrameLayout backdrop = new FrameLayout(this);
    backdrop.setBackgroundColor(0x99000000);
    backdrop.setOnClickListener(new View.OnClickListener() {
      @Override public void onClick(View v) { hideMenu(); }
    });
    LinearLayout panel = new LinearLayout(this);
    panel.setOrientation(LinearLayout.VERTICAL);
    panel.setBackgroundColor(0xFF1F1F1F);
    int pad = dp(16);
    panel.setPadding(pad, pad, pad, pad);
    panel.setClickable(true);

    TextView title = new TextView(this);
    title.setText(pkgs.isEmpty() ? "Nothing else is running" : "Running apps");
    title.setTextColor(0xFF888888);
    title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
    title.setPadding(0, 0, 0, dp(12));
    panel.addView(title);

    android.content.pm.PackageManager pm = getPackageManager();
    for (final String pkg : pkgs) {
      String label = pkg;
      android.graphics.drawable.Drawable icon = null;
      try {
        android.content.pm.ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
        label = pm.getApplicationLabel(ai).toString();
        icon = pm.getApplicationIcon(ai);
      } catch (Exception ignored) { }

      // The card: icon + name, the whole card opens the app.
      LinearLayout card = new LinearLayout(this);
      card.setOrientation(LinearLayout.HORIZONTAL);
      card.setGravity(Gravity.CENTER_VERTICAL);
      card.setBackground(rounded(0xFF2C2C2C, 14));
      card.setPadding(dp(12), 0, dp(10), 0);
      card.setOnClickListener(new View.OnClickListener() {
        @Override public void onClick(View v) {
          hideMenu();
          Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
          if (i != null) launch(i);
        }
      });

      android.widget.ImageView iv = new android.widget.ImageView(this);
      iv.setImageDrawable(icon);
      card.addView(iv, new LinearLayout.LayoutParams(dp(44), dp(44)));

      TextView name = new TextView(this);
      name.setText(label);
      name.setTextColor(Color.WHITE);
      name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
      name.setSingleLine(true);
      name.setEllipsize(android.text.TextUtils.TruncateAt.END);
      name.setPadding(dp(14), 0, dp(8), 0);
      card.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

      TextView close = new TextView(this);
      close.setText("Close");
      close.setTextColor(0xFFFFD9D6);
      close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
      close.setGravity(Gravity.CENTER);
      close.setBackground(rounded(0xFF7A2E2A, 20));
      close.setOnClickListener(new View.OnClickListener() {
        @Override public void onClick(View v) {
          new Thread(new Runnable() {
            @Override public void run() {
              RunningApps.close(java.util.Collections.singletonList(pkg));
              showRunningApps(); // rebuild the list without it
            }
          }).start();
        }
      });
      card.addView(close, new LinearLayout.LayoutParams(dp(96), dp(44)));

      LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(
          LinearLayout.LayoutParams.MATCH_PARENT, dp(72));
      cl.setMargins(0, 0, 0, dp(10));
      panel.addView(card, cl);
    }

    if (!pkgs.isEmpty()) {
      addButton(panel, "Close all", new Runnable() {
        @Override public void run() {
          new Thread(new Runnable() {
            @Override public void run() { RunningApps.close(pkgs); }
          }).start();
          launch(new Intent(EdgeMenuService.this, MainActivity.class)
              .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        }
      });
    }
    addButton(panel, "‹  Menu", new Runnable() {
      @Override public void run() { showMenu(); }
    });

    android.widget.ScrollView scroll = new android.widget.ScrollView(this);
    scroll.addView(panel);
    backdrop.addView(scroll, new FrameLayout.LayoutParams(dp(400),
        FrameLayout.LayoutParams.MATCH_PARENT, Gravity.START));
    menu = backdrop;
    wm.addView(menu, overlayParams(WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT));
  }

  /**
   * The firmware disables Launcher3 again at every boot (the same code that forces Frameo as the
   * home app), so turn it back on with root first. Every app on this frame gets root silently.
   */
  private void openLauncher3() {
    final Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        .setComponent(new ComponentName("com.android.launcher3", "com.android.launcher3.Launcher"));
    new Thread(new Runnable() {
      @Override public void run() {
        try {
          Process p = Runtime.getRuntime().exec(new String[] {
              "su", "-c", "pm enable com.android.launcher3" });
          p.waitFor();
        } catch (Exception e) {
          Log.w(MainActivity.TAG, "Could not re-enable Launcher3", e);
        }
        new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
          @Override public void run() { launch(i); }
        });
      }
    }).start();
  }

  private void launch(Intent i) {
    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    try {
      startActivity(i);
    } catch (Exception e) {
      Log.w(MainActivity.TAG, "Edge menu could not open " + i, e);
    }
  }

  private void hideMenu() {
    if (menu == null) return;
    wm.removeView(menu);
    menu = null;
  }

  private int dp(int v) {
    return Math.round(v * getResources().getDisplayMetrics().density);
  }

  @Override public void onAccessibilityEvent(AccessibilityEvent event) { }

  @Override public void onInterrupt() { }

  @Override
  public void onDestroy() {
    hideMenu();
    if (edge != null) wm.removeView(edge);
    super.onDestroy();
  }
}
