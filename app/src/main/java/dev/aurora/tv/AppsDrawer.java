package dev.aurora.tv;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.List;

/** A separate focus window keeps remote navigation inside the bottom drawer. */
final class AppsDrawer extends Dialog {
    private final LauncherRepository repository;
    private LinearLayout panel;
    private View firstApp;
    private boolean closing;

    AppsDrawer(Context context, LauncherRepository repository) {
        super(context);
        this.repository = repository;
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        panel = new LinearLayout(getContext());
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(44), dp(18), dp(44), dp(12));
        panel.setBackground(shape(0xf21b2939, 24, 0x45ffffff));
        LinearLayout heading = new LinearLayout(getContext());
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(getContext().getString(R.string.all_apps), 23);
        title.setTypeface(null, Typeface.BOLD);
        heading.addView(title, new LinearLayout.LayoutParams(0, dp(42), 1));
        TextView close = text(getContext().getString(R.string.back_home), 14);
        close.setPadding(dp(18), 0, dp(18), 0);
        focusable(close, this::dismiss);
        heading.addView(close, new LinearLayout.LayoutParams(-2, dp(36)));
        panel.addView(heading);

        LinearLayout inputs = new LinearLayout(getContext());
        inputs.setPadding(0, dp(8), 0, dp(12));
        List<LauncherRepository.InputEntry> ports = repository.hdmiInputs();
        for (LauncherRepository.InputEntry port : ports) {
            TextView button = text("▱  " + port.label, 13);
            focusable(button, () -> { if (repository.launch(port)) dismiss(); });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(38), 1);
            params.rightMargin = dp(10);
            inputs.addView(button, params);
        }
        if (ports.isEmpty()) {
            TextView button = text(getContext().getString(R.string.choose_input), 13);
            focusable(button, () -> { if (repository.openInputSettings()) dismiss(); });
            inputs.addView(button, new LinearLayout.LayoutParams(dp(180), dp(38)));
        }
        panel.addView(inputs);

        ScrollView scroll = new ScrollView(getContext());
        scroll.setClipToPadding(false);
        scroll.setPadding(dp(6), dp(6), dp(6), dp(8));
        scroll.setVerticalScrollBarEnabled(false);
        GridLayout grid = new GridLayout(getContext());
        grid.setColumnCount(6);
        grid.setClipChildren(false);
        List<LauncherRepository.AppEntry> apps = repository.apps();
        for (int index = 0; index < apps.size(); index++) {
            LauncherRepository.AppEntry app = apps.get(index);
            LinearLayout card = new LinearLayout(getContext());
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER);
            card.setPadding(dp(8), dp(10), dp(8), dp(8));
            ImageView icon = new ImageView(getContext());
            icon.setImageDrawable(app.icon);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            card.addView(icon, new LinearLayout.LayoutParams(dp(50), dp(50)));
            TextView label = text(app.label, 12);
            label.setSingleLine(true);
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            card.addView(label, new LinearLayout.LayoutParams(-1, dp(30)));
            card.setContentDescription(app.label);
            focusable(card, () -> { if (repository.launch(app)) dismiss(); });
            GridLayout.LayoutParams params = new GridLayout.LayoutParams(
                    GridLayout.spec(index / 6), GridLayout.spec(index % 6, 1f));
            params.width = 0;
            params.height = dp(100);
            params.setMargins(dp(5), dp(5), dp(5), dp(5));
            grid.addView(card, params);
            if (index == 0) firstApp = card;
        }
        scroll.addView(grid, new FrameLayout.LayoutParams(-1, -2));
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(panel);
        setCanceledOnTouchOutside(true);
    }

    @Override protected void onStart() {
        super.onStart();
        Window window = getWindow();
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.setGravity(Gravity.BOTTOM);
        window.setLayout(-1, Math.round(getContext().getResources().getDisplayMetrics().heightPixels * .84f));
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setDimAmount(.42f);
        window.getDecorView().setSystemUiVisibility(5894 | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        panel.setTranslationY(dp(300));
        panel.animate().translationY(0).setDuration(220).start();
        if (firstApp != null) firstApp.requestFocus();
    }

    @Override public void dismiss() {
        if (closing) return;
        closing = true;
        if (panel == null || !panel.isAttachedToWindow()) { super.dismiss(); return; }
        panel.animate().cancel();
        panel.animate().translationY(panel.getHeight()).setDuration(170)
                .withEndAction(() -> AppsDrawer.super.dismiss()).start();
    }

    /** Teardown must not leave an animation holding the Activity window. */
    void dismissImmediately() {
        closing = true;
        if (panel != null) panel.animate().cancel();
        super.dismiss();
    }

    @Override public boolean onKeyDown(int key, KeyEvent event) {
        if (closing) return true;
        return super.onKeyDown(key, event);
    }

    private void focusable(View view, Runnable action) {
        view.setFocusable(true);
        view.setClickable(true);
        view.setBackground(shape(0x543d5369, 12, 0x25ffffff));
        view.setOnClickListener(v -> action.run());
        view.setOnFocusChangeListener((v, focused) -> {
            v.setBackground(shape(focused ? 0xee56738d : 0x543d5369, 12,
                    focused ? 0xffffffff : 0x25ffffff));
            v.animate().scaleX(focused ? 1.035f : 1f).scaleY(focused ? 1.035f : 1f)
                    .setDuration(140).start();
        });
    }

    private TextView text(String value, int size) {
        TextView view = new TextView(getContext());
        view.setText(value);
        view.setTextColor(Color.WHITE);
        view.setTextSize(size);
        view.setGravity(Gravity.CENTER);
        return view;
    }

    private GradientDrawable shape(int color, int radius, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private int dp(float value) {
        return Math.round(value * getContext().getResources().getDisplayMetrics().density);
    }
}
