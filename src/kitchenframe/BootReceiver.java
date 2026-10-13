package kitchenframe;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Brings the kiosk up after boot. The firmware forces Frameo as the home app and launches it
 * at boot too, so the kiosk is started twice: once now and once a little later, in case
 * Frameo's own start lands on top of the first one.
 */
public class BootReceiver extends BroadcastReceiver {
  static final long SECOND_START_MS = 20 * 1000;

  @Override
  public void onReceive(final Context context, Intent intent) {
    if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
    Log.i(MainActivity.TAG, "Boot completed: starting kiosk");
    start(context);
    final PendingResult pending = goAsync();
    new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
      @Override public void run() {
        start(context);
        pending.finish();
      }
    }, SECOND_START_MS);
  }

  private static void start(Context context) {
    context.startActivity(new Intent(context, MainActivity.class)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
  }
}
