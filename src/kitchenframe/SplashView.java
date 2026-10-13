package kitchenframe;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.InputStream;

/**
 * Carries on from the boot animation so start-up looks like one continuous screen, with a line
 * of status underneath ("Connecting to Wi-Fi…", "Loading slideshow…") until the slideshow is up.
 *
 * splash.png is the boot animation's last intro frame (640×576, pure black ground). Android draws
 * the boot animation centred at 1:1 pixels, and so does this view, so the logo doesn't move when
 * one hands over to the other. The orange segment sweeping the loading bar is drawn here rather
 * than played as 42 frames, which would cost the WebViews memory they need.
 */
class SplashView extends FrameLayout {
  // Loading-bar track inside splash.png, measured from the frames (2026-10-10).
  static final int FRAME_W = 640, FRAME_H = 576;
  static final int TRACK_X = 131, TRACK_Y = 532, TRACK_W = 378, TRACK_H = 11;
  static final int SEGMENT_COLOR = 0xFFEBA42B;
  static final long LOOP_MS = 1400;  // one sweep, as in the boot animation

  private final TextView status;
  private final Bar bar;

  SplashView(Context c) {
    super(c);
    setBackgroundColor(Color.BLACK);
    setClickable(true);

    Bitmap logo = null;
    try (InputStream in = c.getAssets().open("splash.png")) {
      BitmapFactory.Options o = new BitmapFactory.Options();
      o.inScaled = false;
      logo = BitmapFactory.decodeStream(in, null, o);
    } catch (Exception e) {
      android.util.Log.w(MainActivity.TAG, "splash.png missing", e);
    }
    // One frame-sized box centred on screen - exactly where Android draws the boot animation.
    // The image fills it and the bar sits on the track at the measured offset inside it.
    FrameLayout frame = new FrameLayout(c);
    addView(frame, new LayoutParams(FRAME_W, FRAME_H, Gravity.CENTER));

    ImageView image = new ImageView(c);
    image.setImageBitmap(logo);
    image.setScaleType(ImageView.ScaleType.CENTER);
    frame.addView(image, new LayoutParams(FRAME_W, FRAME_H));

    bar = new Bar(c);
    LayoutParams bl = new LayoutParams(TRACK_W, TRACK_H, Gravity.TOP | Gravity.START);
    bl.leftMargin = TRACK_X;
    bl.topMargin = TRACK_Y;
    if (logo != null) frame.addView(bar, bl); // the bar's position belongs to that image

    // The status line: centred on screen, then pushed down to just below the frame.
    status = new TextView(c);
    status.setTextColor(0xFF9AA3AE);
    status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
    status.setGravity(Gravity.CENTER_HORIZONTAL);
    addView(status, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT,
        Gravity.CENTER));
    status.setTranslationY(FRAME_H / 2f + 22);
  }

  void setStatus(String text) {
    if (!text.contentEquals(status.getText())) {
      status.setText(text);
      android.util.Log.i(MainActivity.TAG, "Splash: " + text);
    }
  }

  boolean isShowing() { return getVisibility() == VISIBLE && getAlpha() > 0f; }

  /** Fade out, then leave the view tree drawing nothing. */
  void dismiss() {
    if (getVisibility() != VISIBLE) return;
    animate().alpha(0f).setDuration(500).withEndAction(new Runnable() {
      @Override public void run() { setVisibility(GONE); }
    }).start();
  }

  /** The orange slash-ended segment sweeping across the track, forever until hidden. */
  private class Bar extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final int segment = 110;
    private final int slant = 4;

    Bar(Context c) {
      super(c);
      paint.setColor(SEGMENT_COLOR);
    }

    @Override protected void onDraw(Canvas canvas) {
      if (SplashView.this.getVisibility() != VISIBLE) return;
      float phase = (SystemClock.uptimeMillis() % LOOP_MS) / (float) LOOP_MS;
      float x = -segment + phase * (getWidth() + segment);
      path.reset();
      path.moveTo(x + slant, 0);
      path.lineTo(x + segment + slant, 0);
      path.lineTo(x + segment, getHeight());
      path.lineTo(x, getHeight());
      path.close();
      canvas.save();
      canvas.clipRect(0, 0, getWidth(), getHeight());
      canvas.drawPath(path, paint);
      canvas.restore();
      postInvalidateOnAnimation();
    }
  }
}
