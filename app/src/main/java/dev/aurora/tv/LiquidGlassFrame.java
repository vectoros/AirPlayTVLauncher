package dev.aurora.tv;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

/** Wallpaper-only refraction and blur, with crisp content above an original glass material. */
public final class LiquidGlassFrame extends FrameLayout {
    private final WallpaperView wallpaper;
    private final View backdrop;
    private final View material;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rim = new RectF();
    private final RectF inside = new RectF();
    private final Path lensRing = new Path();
    private final Matrix glintMatrix = new Matrix();
    private final int[] origin = new int[2], scene = new int[2];
    private final float radius;
    private Shader border, sheen, innerShade, glint;
    private boolean liquid, glassFocused, animationsEnabled = true, disposed;
    private float focusAmount, sweep = 1;
    private ValueAnimator focusAnimator;

    public LiquidGlassFrame(Context context, WallpaperView wallpaper) {
        super(context);
        this.wallpaper = wallpaper;
        radius = dp(16);
        GradientDrawable fallback = new GradientDrawable();
        fallback.setColor(0x303a5369);
        fallback.setCornerRadius(radius);
        fallback.setStroke(Math.max(1, Math.round(dp(1))), 0x45ffffff);
        setBackground(fallback);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        setClipToOutline(true);
        backdrop = new View(context) {
            @Override protected void onDraw(Canvas canvas) { drawBackdrop(canvas); }
        };
        material = new View(context) {
            @Override protected void onDraw(Canvas canvas) { drawMaterial(canvas); }
        };
        backdrop.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        material.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(backdrop, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        addView(material, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        applyBlur();
    }

    public boolean isLiquidEnabled() { return liquid; }

    /** Does not change children, click handling, or the caller's focus/scale animation. */
    public void setLiquidEnabled(boolean enabled) {
        if (disposed || liquid == enabled) return;
        liquid = enabled;
        stopAnimation();
        focusAmount = enabled && glassFocused ? 1 : 0;
        sweep = 1;
        applyBlur();
        refreshBackdrop();
        material.invalidate();
    }

    public void setGlassFocused(boolean focused) {
        if (disposed || glassFocused == focused) return;
        glassFocused = focused;
        stopAnimation();
        float target = liquid && focused ? 1 : 0;
        if (!liquid || !animationsEnabled || !isAttachedToWindow() || !isShown()) {
            focusAmount = target;
            sweep = 1;
            material.invalidate();
            return;
        }
        final float start = focusAmount;
        focusAnimator = ValueAnimator.ofFloat(0, 1);
        focusAnimator.setDuration(focused ? 360 : 220);
        focusAnimator.setInterpolator(new DecelerateInterpolator());
        focusAnimator.addUpdateListener(animation -> {
            float progress = (float) animation.getAnimatedValue();
            focusAmount = start + (target - start) * progress;
            sweep = focused ? progress : 1;
            material.invalidate();
        });
        focusAnimator.start();
    }

    public void setAnimationsEnabled(boolean enabled) {
        animationsEnabled = enabled;
        if (!enabled) settleAnimation();
    }

    public void refreshBackdrop() {
        if (!disposed) backdrop.invalidate();
    }

    public void dispose() {
        disposed = true;
        stopAnimation();
        if (Build.VERSION.SDK_INT >= 31) backdrop.setRenderEffect(null);
    }

    private void applyBlur() {
        if (Build.VERSION.SDK_INT >= 31) {
            float blur = dp(liquid ? 7 : 22);
            backdrop.setRenderEffect(RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP));
        }
    }

    private void drawBackdrop(Canvas canvas) {
        if (disposed) return;
        backdrop.getLocationOnScreen(origin);
        wallpaper.getLocationOnScreen(scene);
        sample(canvas, liquid ? 1.025f : 1f);
        if (liquid) {
            // A thicker edge samples a different magnification: the wallpaper visibly
            // bends at the rim without an API 33 shader or intermediate bitmap copies.
            int save = canvas.save();
            canvas.clipPath(lensRing);
            sample(canvas, 1.09f);
            canvas.restoreToCount(save);
            canvas.drawColor(0x381b2939);
        } else {
            canvas.drawColor(0x55425769);
        }
    }

    private void sample(Canvas canvas, float zoom) {
        int save = canvas.save();
        canvas.scale(zoom, zoom, getWidth() * .5f, getHeight() * .5f);
        canvas.translate(scene[0] - origin[0], scene[1] - origin[1]);
        wallpaper.drawScene(canvas, wallpaper.getWidth(), wallpaper.getHeight());
        canvas.restoreToCount(save);
    }

    private void drawMaterial(Canvas canvas) {
        if (disposed || sheen == null) return;
        if (!liquid) {
            if (glassFocused) {
                paint.setShader(null);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0x08ffffff);
                canvas.drawRoundRect(rim, radius, radius, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(1));
                paint.setColor(0xddffffff);
                canvas.drawRoundRect(rim, radius, radius, paint);
                paint.setStyle(Paint.Style.FILL);
            }
            return;
        }
        // This view precedes all user content: the optical layers never blur or wash
        // over app icons/text. Shaders and paths are rebuilt only when size changes.
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(sheen);
        paint.setAlpha(Math.round(175 + focusAmount * 80));
        canvas.drawRoundRect(rim, radius, radius, paint);
        paint.setAlpha(255);
        paint.setShader(innerShade);
        canvas.drawPath(lensRing, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.2f));
        paint.setShader(border);
        paint.setAlpha(Math.round(170 + focusAmount * 85));
        canvas.drawRoundRect(rim, radius - dp(.6f), radius - dp(.6f), paint);
        paint.setShader(null);
        paint.setColor(0x44ffffff);
        paint.setAlpha(Math.round(30 + focusAmount * 45));
        paint.setStrokeWidth(dp(.65f));
        canvas.drawRoundRect(inside, Math.max(0, radius - dp(3)),
                Math.max(0, radius - dp(3)), paint);
        if (glassFocused && sweep > 0 && sweep < 1) {
            glintMatrix.setTranslate((sweep * 1.6f - .3f) * getWidth(), 0);
            glint.setLocalMatrix(glintMatrix);
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(glint);
            paint.setAlpha(Math.round(100 * (float) Math.sin(Math.PI * sweep)));
            canvas.drawRoundRect(rim, radius, radius, paint);
        }
        paint.setShader(null);
        paint.setAlpha(255);
        paint.setStyle(Paint.Style.FILL);
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (width <= 0 || height <= 0) return;
        float half = dp(.6f), edge = Math.min(dp(7), Math.min(width, height) * .15f);
        rim.set(half, half, width - half, height - half);
        inside.set(dp(3), dp(3), width - dp(3), height - dp(3));
        lensRing.reset();
        lensRing.setFillType(Path.FillType.EVEN_ODD);
        lensRing.addRoundRect(0, 0, width, height, radius, radius, Path.Direction.CW);
        lensRing.addRoundRect(edge, edge, width - edge, height - edge,
                radius - edge, radius - edge, Path.Direction.CW);
        border = new LinearGradient(0, 0, width * .7f, height,
                new int[]{0xeaffffff, 0x60d9f7ff, 0x12ffffff, 0x99d3edff},
                new float[]{0, .25f, .64f, 1}, Shader.TileMode.CLAMP);
        sheen = new LinearGradient(0, 0, width * .15f, height,
                new int[]{0x39ffffff, 0x12ecf7ff, 0x02ffffff, 0x1403111c},
                new float[]{0, .3f, .57f, 1}, Shader.TileMode.CLAMP);
        innerShade = new LinearGradient(0, 0, 0, height,
                new int[]{0x00ffffff, 0x12040c19, 0x44030c19},
                new float[]{0, .45f, 1}, Shader.TileMode.CLAMP);
        float band = Math.max(dp(24), width * .2f);
        glint = new LinearGradient(-band, 0, band, height * .5f,
                new int[]{0x00ffffff, 0x65ffffff, 0x00ffffff},
                new float[]{0, .5f, 1}, Shader.TileMode.CLAMP);
    }

    private void stopAnimation() {
        if (focusAnimator != null) {
            focusAnimator.cancel();
            focusAnimator.removeAllUpdateListeners();
            focusAnimator = null;
        }
    }

    private void settleAnimation() {
        stopAnimation();
        focusAmount = liquid && glassFocused ? 1 : 0;
        sweep = 1;
        if (material != null) material.invalidate();
    }

    @Override protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility != VISIBLE) settleAnimation();
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility != VISIBLE) settleAnimation();
    }

    @Override protected void onDetachedFromWindow() {
        settleAnimation();
        super.onDetachedFromWindow();
    }

    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }
}
