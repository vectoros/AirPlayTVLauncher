package dev.aurora.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.RectF;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Native landscape and local-photo slideshow, sharing the same frame with glass panels. */
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
    private static final String[] EFFECTS = {"淡入淡出", "缓慢推拉", "横向滑移"};
    private static final long TRANSITION_MS = 1800;
    private final Paint photoPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF photoRect = new RectF();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private List<File> photos = Collections.emptyList();
    private Bitmap currentPhoto;
    private Bitmap nextPhoto;
    private int currentIndex = -1;
    private int nextIndex = -1;
    private int effect;
    private int intervalSeconds;
    private boolean photoMode;
    private boolean loading;
    private boolean exhausted;
    private volatile boolean closed;
    private volatile int generation;
    // Animation time advances only while visible, so resume never jumps a transition.
    private long photoElapsed;
    private long transitionElapsed = -1;
    private float transition;

    private final Runnable frame = new Runnable() {
        @Override public void run() {
            if (!running || !attached || getWindowVisibility() != VISIBLE) return;
            long now = SystemClock.uptimeMillis();
            phase += Math.min(100L, now - lastFrame) / 42000f;
            if (phase > Math.PI * 2) phase -= (float) (Math.PI * 2);
            advancePhotos(Math.min(100L, now - lastFrame));
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
        photoMode = preferences.getBoolean("wallpaper_photos", false);
        effect = Math.floorMod(preferences.getInt("wallpaper_effect", 1), EFFECTS.length);
        int savedInterval = preferences.getInt("wallpaper_interval", 30);
        intervalSeconds = savedInterval == 15 || savedInterval == 60 ? savedInterval : 30;
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

    /** All configuration methods are UI-thread calls; files must be durable app-owned copies. */
    public void setPhotos(List<File> files) {
        List<File> replacement = files == null ? Collections.emptyList() : new ArrayList<>(files);
        if (photos.equals(replacement)) return;
        cancelLoad();
        photos = replacement;
        clearPhotos();
        exhausted = false;
        ensurePhoto();
        redraw();
    }

    public boolean isUsingPhotos() { return photoMode; }
    /** True after an empty/corrupt album has resolved to the procedural fallback. */
    public boolean isPhotoFallback() {
        return photoMode && currentPhoto == null && (photos.isEmpty() || exhausted);
    }
    public int getPhotoCount() { return photos.size(); }
    public boolean isTransitioning() { return photoMode && transitionElapsed >= 0; }

    public void nextPhoto() {
        if (!photoMode || currentPhoto == null || photos.size() < 2) return;
        photoElapsed = intervalSeconds * 1000L;
        ensurePhoto();
    }

    public void usePhotos(boolean value) {
        photoMode = value;
        preferences.edit().putBoolean("wallpaper_photos", value).apply();
        cancelLoad();
        if (!value) clearPhotos();
        exhausted = false;
        ensurePhoto();
        redraw();
    }

    public int getEffect() { return effect; }
    public String getEffectName() { return EFFECTS[effect]; }
    public void setEffect(int value) {
        effect = Math.floorMod(value, EFFECTS.length);
        preferences.edit().putInt("wallpaper_effect", effect).apply();
        redraw();
    }

    public int getIntervalSeconds() { return intervalSeconds; }
    public void setIntervalSeconds(int value) {
        intervalSeconds = value == 15 || value == 60 ? value : 30;
        preferences.edit().putInt("wallpaper_interval", intervalSeconds).apply();
    }

    /** Activity.onDestroy must release the decoder and the two retained bitmaps. */
    public void close() {
        closed = true;
        running = false;
        removeCallbacks(frame);
        cancelLoad();
        loader.shutdownNow();
        clearPhotos();
        frameListener = null;
    }

    private boolean active() {
        return !closed && running && attached && getWindowVisibility() == VISIBLE;
    }

    private void cancelLoad() { generation++; loading = false; }

    private void clearPhotos() {
        // Published bitmaps may still be referenced by a hardware display list/glass
        // RenderNode. Drop ownership and let Android reclaim them once rendering ends.
        currentPhoto = nextPhoto = null;
        currentIndex = nextIndex = -1;
        photoElapsed = 0;
        transitionElapsed = -1;
        transition = 0;
    }

    private void redraw() {
        invalidate();
        if (frameListener != null) frameListener.run();
    }

    private void ensurePhoto() {
        if (!active() || !photoMode || photos.isEmpty() || loading || exhausted
                || nextPhoto != null || (currentPhoto != null && photos.size() == 1)) return;
        final int ticket = generation;
        final List<File> files = new ArrayList<>(photos);
        final int start = currentIndex;
        loading = true;
        loader.execute(() -> {
            Bitmap decoded = null;
            int index = -1;
            // One bounded pass; a corrupt album falls back to the procedural wallpaper.
            for (int offset = 1; offset <= files.size(); offset++) {
                if (closed || ticket != generation) break;
                int candidate = Math.floorMod(start + offset, files.size());
                if (candidate == start) continue;
                decoded = decodePhoto(files.get(candidate));
                if (decoded != null) { index = candidate; break; }
            }
            final Bitmap result = decoded;
            final int resultIndex = index;
            main.post(() -> {
                if (closed || ticket != generation || !active()) {
                    if (result != null) result.recycle();
                    return;
                }
                loading = false;
                if (result == null) {
                    exhausted = true;
                    redraw();
                    return;
                }
                if (currentPhoto == null) {
                    currentPhoto = result;
                    currentIndex = resultIndex;
                    photoElapsed = 0;
                    ensurePhoto();
                } else {
                    nextPhoto = result;
                    nextIndex = resultIndex;
                }
                redraw();
            });
        });
    }

    private static Bitmap decodePhoto(File file) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            if (options.outWidth <= 0 || options.outHeight <= 0) return null;
            options.inSampleSize = 1;
            // Bound either dimension even for huge panoramas; max ~8 MB per ARGB bitmap.
            while (options.outWidth / options.inSampleSize > 1920
                    || options.outHeight / options.inSampleSize > 1080) options.inSampleSize *= 2;
            options.inJustDecodeBounds = false;
            return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        } catch (RuntimeException | OutOfMemoryError ignored) {
            return null;
        }
    }

    private void advancePhotos(long delta) {
        if (!photoMode || currentPhoto == null) return;
        photoElapsed += delta;
        if (nextPhoto == null) return;
        if (transitionElapsed < 0 && photoElapsed >= intervalSeconds * 1000L) transitionElapsed = 0;
        if (transitionElapsed < 0) return;
        transitionElapsed += delta;
        float t = Math.min(1f, transitionElapsed / (float) TRANSITION_MS);
        transition = t * t * (3 - 2 * t);
        if (t >= 1) {
            // The previous frame may still be queued on RenderThread; never recycle it.
            currentPhoto = nextPhoto;
            currentIndex = nextIndex;
            nextPhoto = null;
            nextIndex = -1;
            photoElapsed = TRANSITION_MS;
            transitionElapsed = -1;
            transition = 0;
            ensurePhoto();
        }
    }

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
        if (active()) {
            ensurePhoto();
            lastFrame = SystemClock.uptimeMillis();
            postDelayed(frame, 40);
        } else {
            cancelLoad();
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
        cancelLoad();
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
        if (photoMode && currentPhoto != null) {
            drawPhotos(canvas, width, height);
            return;
        }
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

    private void drawPhotos(Canvas canvas, int width, int height) {
        int save = canvas.save();
        canvas.clipRect(0, 0, width, height);
        canvas.drawColor(Color.BLACK);
        float zoom = effect == 1 ? photoZoom(photoElapsed) : 1f;
        float slide = effect == 2 ? width * .12f * transition : 0;
        drawPhoto(canvas, currentPhoto, width, height, zoom, -slide, 255);
        if (nextPhoto != null && transitionElapsed >= 0) {
            float incomingZoom = effect == 1 ? photoZoom(transitionElapsed) : 1f;
            float incomingSlide = effect == 2 ? width * .12f * (1 - transition) : 0;
            drawPhoto(canvas, nextPhoto, width, height, incomingZoom, incomingSlide,
                    Math.round(255 * transition));
        }
        // Uniform dimming protects the white clock and app titles over bright photos.
        canvas.drawColor(0x55050912);
        canvas.restoreToCount(save);
    }

    private float photoZoom(long elapsed) {
        float progress = (float) (0.5 - 0.5 * Math.cos(Math.PI
                * elapsed / (double) (intervalSeconds * 1000L + TRANSITION_MS)));
        return 1.02f + progress * .075f;
    }

    private void drawPhoto(Canvas canvas, Bitmap bitmap, int width, int height,
                           float zoom, float slide, int alpha) {
        float scale = Math.max(width / (float) bitmap.getWidth(), height / (float) bitmap.getHeight());
        // Overscan keeps the horizontal transition covered edge to edge.
        scale *= zoom * (effect == 2 ? 1.24f : 1f);
        float w = bitmap.getWidth() * scale;
        float h = bitmap.getHeight() * scale;
        float x = (width - w) / 2 + slide;
        float y = (height - h) / 2;
        photoRect.set(x, y, x + w, y + h);
        photoPaint.setAlpha(alpha);
        canvas.drawBitmap(bitmap, null, photoRect, photoPaint);
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
