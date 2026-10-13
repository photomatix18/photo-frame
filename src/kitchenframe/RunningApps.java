package kitchenframe;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Apps left running in the background: every package with a live process AND a task in
 * Android's recents list, minus the kiosk and system plumbing. Used by the edge menu's Running
 * apps page and by the kiosk's tidy-up when it goes idle. Reads dumpsys/ps and force-stops as
 * root - every app on this frame gets root silently. The firmware's own recents screen doesn't
 * work, which is why this exists.
 */
final class RunningApps {
  private static final Set<String> HIDDEN = new HashSet<String>(
      Arrays.asList("com.android.launcher3", "com.android.systemui", "android",
          "com.android.inputmethod.latin")); // the on-screen keyboard - never worth closing

  private RunningApps() { }

  /** Call off the main thread. */
  static List<String> list(Context c) {
    String out = su("dumpsys activity recents; echo ===PS===; ps");
    String[] halves = out.split("===PS===");
    Set<String> procs = new HashSet<String>();
    if (halves.length > 1) {
      for (String line : halves[1].split("\n")) {
        String[] cols = line.trim().split("\\s+");
        String name = cols[cols.length - 1];
        int colon = name.indexOf(':');
        procs.add(colon > 0 ? name.substring(0, colon) : name);
      }
    }
    List<String> pkgs = new ArrayList<String>();
    for (String line : halves[0].split("\n")) {
      int i = line.indexOf("realActivity=");
      if (i < 0) continue;
      String comp = line.substring(i + "realActivity=".length()).trim();
      int slash = comp.indexOf('/');
      String pkg = slash > 0 ? comp.substring(0, slash) : comp;
      if (pkg.equals(c.getPackageName()) || HIDDEN.contains(pkg)) continue;
      if (procs.contains(pkg) && !pkgs.contains(pkg)) pkgs.add(pkg);
    }
    return pkgs;
  }

  /** Call off the main thread. */
  static void close(List<String> pkgs) {
    if (pkgs.isEmpty()) return;
    StringBuilder cmd = new StringBuilder();
    for (String p : pkgs) cmd.append("am force-stop ").append(p).append("; ");
    su(cmd.toString());
    Log.i(MainActivity.TAG, "Closed " + pkgs);
  }

  static String su(String cmd) {
    try {
      Process p = Runtime.getRuntime().exec(new String[] { "su", "-c", cmd });
      BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
      StringBuilder sb = new StringBuilder();
      String line;
      while ((line = r.readLine()) != null) sb.append(line).append('\n');
      p.waitFor();
      return sb.toString();
    } catch (Exception e) {
      Log.w(MainActivity.TAG, "su failed: " + cmd, e);
      return "";
    }
  }
}
