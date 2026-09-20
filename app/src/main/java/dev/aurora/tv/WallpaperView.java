package dev.aurora.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

/** Original procedural landscape; no downloaded artwork, native Canvas, at most 25 fps. */
public final class WallpaperView extends View {
    private static final String[] NAMES = {"极光海岸", "紫暮山峦", "暮色流金"};
    // Sky top, sky bottom, first glow, second glow, far hill, near hill.
    private static final int[][] COLORS = {
        {0xff061229, 0xff243950, 0xff258d94, 0xff69549c, 0xff192c47, 0xff101d31},
        {0xff120e2a, 0xff47425e, 0xff926888, 0xff535db0, 0xff302c49, 0xff1a2036},
        {0xff131f2d, 0xff685344, 0xffaa7c50, 0xff547d83, 0xff3b3c45, 0xff202d36}
    };
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path farHill = new Path();
    private final Path nearHill = new Path();
    private final SharedPreferences preferences;
    private final Shader[] glow = new Shader[2];
    private Shader sky;
    private Shader farShade;
    private Shader nearShade;
    private Shader vignette;
    private int palette;
    private int sceneWidth;
    private int sceneHeight;
    private boolean running;
    private boolean attached;
    private float phase;
    private long lastFrame;
    private Runnable frameListener;

    private final Runnable frame = new Runnable() {
        @Override public void run() {
            if (!running || !attached || getWindowVisibility() != VISIBLE) return;
            long now = SystemClock.uptimeMillis();
            phase += Math.min(100L, now - lastFrame) / 42000f;
            if (phase > Math.PI * 2) phase -= (float) (Math.PI * 2);
            lastFrame = now;
            invalidate();
            if (frameListener != null) frameListener.run();
            postDelayed(this, 40);
        }
    };

    public WallpaperView(Context context) { this(context, null); }

    public WallpaperView(Context context, AttributeSet attrs) {
        super(context, attrs);
        preferences = context.getSharedPreferences("aurora_launcher", Context.MODE_PRIVATE);
        palette = Math.floorMod(preferences.getInt("wallpaper_palette", 0), COLORS.length);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setFocusable(false);
    }

    /** Call from Activity.onResume/onPause; detached or hidden views also stop scheduling. */
    public void setRunning(boolean value) {
        running = value;
        scheduleFrames();
    }

    /** Optional: invalidate a separate glass surface whenever the background moves. */
    public void setOnFrameListener(Runnable listener) { frameListener = listener; }

    public String getPaletteName() { return NAMES[palette]; }

    public void cyclePalette() {
        palette = (palette + 1) % COLORS.length;
        preferences.edit().putInt("wallpaper_palette", palette).apply();
        sceneWidth = 0;
        invalidate();
        if (frameListener != null) frameListener.run();
    }

    private void scheduleFrames() {
        removeCallbacks(frame);
        if (running && attached && getWindowVisibility() == VISIBLE) {
            lastFrame = SystemClock.uptimeMillis();
            postDelayed(frame, 40);
        }
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        scheduleFrames();
    }

    @Override protected void onDetachedFromWindow() {
        attached = false;
        removeCallbacks(frame);
        super.onDetachedFromWindow();
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        scheduleFrames();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        drawScene(canvas, getWidth(), getHeight());
    }

    /**
     * Draw the identical current frame in full-wallpaper coordinates. For a glass panel,
     * translate its canvas by (-panelX, -panelY), then pass the wallpaper's full dimensions.
     * This does not advance animation. Call on the UI thread, as with any View drawing.
     */
    public void drawScene(Canvas canvas, int width, int height) {
        if (width <= 0 || height <= 0) return;
        if (sceneWidth != width || sceneHeight != height) prepareScene(width, height);
        paint.setAlpha(255);
        paint.setShader(sky);
        canvas.drawRect(0, 0, width, height, paint);

        float drift = (float) Math.sin(phase);
        float swell = (float) Math.cos(phase * 0.8f);
        drawGlow(canvas, width, height, 0, width * (0.31f + drift * 0.022f),
                height * (0.32f + swell * 0.018f), 1.2f, 0.76f);
        drawGlow(canvas, width, height, 1, width * (0.82f - drift * 0.016f),
                height * (0.32f - swell * 0.022f), 0.91f, 0.85f);

        paint.setShader(farShade);
        canvas.drawPath(farHill, paint);
        paint.setShader(nearShade);
        canvas.drawPath(nearHill, paint);
        paint.setShader(vignette);
        canvas.drawRect(0, 0, width, height, paint);
        paint.setShader(null);
    }

    private void drawGlow(Canvas canvas, int width, int height, int index,
                          float x, float y, float scaleX, float scaleY) {
        int save = canvas.save();
        canvas.translate(x, y);
        canvas.scale(scaleX, scaleY * height / width);
        paint.setShader(glow[index]);
        float radius = width * 0.72f;
        canvas.drawRect(-radius, -radius, radius, radius, paint);
        canvas.restoreToCount(save);
    }

    private void prepareScene(int width, int height) {
        sceneWidth = width;
        sceneHeight = height;
        int[] colors = COLORS[palette];
        sky = new LinearGradient(0, 0, width * .2f, height,
                colors[0], colors[1], Shader.TileMode.CLAMP);
        for (int i = 0; i < 2; i++) {
            int color = colors[i + 2];
            glow[i] = new RadialGradient(0, 0, width * .72f,
                    new int[]{withAlpha(color, 160), withAlpha(color, 75), withAlpha(color, 0)},
                    new float[]{0, .43f, 1}, Shader.TileMode.CLAMP);
        }
        farShade = new LinearGradient(0, height * .53f, 0, height,
                colors[4], colors[5], Shader.TileMode.CLAMP);
        nearShade = new LinearGradient(0, height * .68f, 0, height,
                colors[5], colors[0], Shader.TileMode.CLAMP);
        vignette = new RadialGradient(width * .52f, height * .35f, width * .8f,
                new int[]{Color.TRANSPARENT, 0x19030a14, 0x99030914},
                new float[]{0, .5f, 1}, Shader.TileMode.CLAMP);

        farHill.reset();
        farHill.moveTo(0, height * .72f);
        farHill.cubicTo(width * .12f, height * .68f, width * .23f, height * .46f,
                width * .39f, height * .57f);
        farHill.cubicTo(width * .55f, height * .72f, width * .68f, height * .62f,
                width, height * .56f);
        farHill.lineTo(width, height);
        farHill.lineTo(0, height);
        farHill.close();
        nearHill.reset();
        nearHill.moveTo(0, height * .84f);
        nearHill.cubicTo(width * .2f, height * .89f, width * .37f, height * .76f,
                width * .57f, height * .72f);
        nearHill.cubicTo(width * .78f, height * .65f, width * .87f, height * .76f,
                width, height * .74f);
        nearHill.lineTo(width, height);
        nearHill.lineTo(0, height);
        nearHill.close();
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00ffffff) | (alpha << 24);
    }
}
