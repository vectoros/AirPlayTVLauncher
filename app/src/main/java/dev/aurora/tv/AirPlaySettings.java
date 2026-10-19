package dev.aurora.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputFilter;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** Remote-friendly controls for the independently installed receiver. */
final class AirPlaySettings {
    private final Activity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private AlertDialog dialog;
    private TextView status, name, permission, advanced;
    private Switch enabled, pin;
    private AirPlayCompanion.State state;
    private boolean updating;

    AirPlaySettings(Activity activity) { this.activity = activity; }

    void show() {
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(8), dp(22), dp(8));
        status = label(activity.getString(R.string.airplay_loading));
        content.addView(status);
        enabled = toggle(R.string.airplay_enabled);
        content.addView(enabled);
        name = action("", this::editName);
        content.addView(name);
        pin = toggle(R.string.airplay_require_pin);
        content.addView(pin);
        content.addView(label(activity.getString(R.string.airplay_settings_help)));
        permission = action(activity.getString(R.string.airplay_allow_display), () -> open(
                new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + AirPlayCompanion.PACKAGE))));
        content.addView(permission);
        advanced = action(activity.getString(R.string.airplay_advanced), () -> open(AirPlayCompanion.advancedSettings()));
        content.addView(advanced);
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(content);
        dialog = new AlertDialog.Builder(activity).setTitle(R.string.airplay_receiver).setView(scroll)
                .setNegativeButton(R.string.back, null).setNeutralButton(R.string.airplay_refresh, null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> refresh()));
        dialog.setOnDismissListener(d -> handler.removeCallbacksAndMessages(null));
        enabled.setOnCheckedChangeListener((button, checked) -> {
            if (!updating) { Bundle change = new Bundle(); change.putBoolean("enabled", checked); save(change); }
        });
        pin.setOnCheckedChangeListener((button, checked) -> {
            if (updating) return;
            confirmChange(() -> { Bundle change = new Bundle(); change.putBoolean("require_pin", checked); save(change); });
        });
        dialog.show();
        setBusy(true);
        refresh();
    }

    void refreshIfVisible() { if (visible()) refresh(); }
    void close() { if (dialog != null) dialog.dismiss(); handler.removeCallbacksAndMessages(null); }
    private boolean visible() { return dialog.isShowing() && !activity.isFinishing() && !activity.isDestroyed(); }
    private void refresh() { AirPlayCompanion.status(activity, value -> { if (visible()) render(value); }); }
    private void render(AirPlayCompanion.State value) {
        state = value;
        updating = true;
        enabled.setChecked(value.enabled);
        pin.setChecked(value.requirePin);
        name.setText(activity.getString(R.string.airplay_device_name, value.name));
        int message = !value.installed ? R.string.airplay_missing : !value.compatible ? R.string.airplay_update_required
                : "ERROR".equals(value.phase) ? R.string.airplay_failed : !value.enabled ? R.string.airplay_off
                : value.casting ? R.string.airplay_casting : !value.overlay ? R.string.airplay_permission_needed
                : "RUNNING".equals(value.phase) ? R.string.airplay_ready : R.string.airplay_starting;
        status.setText(activity.getString(message));
        permission.setVisibility(value.compatible && !value.overlay ? View.VISIBLE : View.GONE);
        setBusy(false);
        updating = false;
    }
    private void setBusy(boolean busy) {
        boolean editable = !busy && state != null && state.compatible;
        enabled.setEnabled(editable); name.setEnabled(editable); pin.setEnabled(editable);
        advanced.setEnabled(!busy && state != null && state.installed);
    }
    private void save(Bundle change) {
        setBusy(true);
        AirPlayCompanion.configure(activity, change, value -> {
            if (!visible()) return;
            render(value);
            handler.postDelayed(() -> { if (visible()) refresh(); }, 1200);
        });
    }
    private void confirmChange(Runnable apply) {
        new AlertDialog.Builder(activity).setMessage(R.string.airplay_change_warning)
                .setPositiveButton(R.string.ok, (d, which) -> apply.run())
                .setNegativeButton(R.string.cancel, (d, which) -> refresh())
                .setOnCancelListener(d -> refresh()).show();
    }
    private void editName() {
        if (state == null) return;
        EditText input = new EditText(activity);
        input.setSingleLine(true); input.setText(state.name);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(40)});
        AlertDialog edit = new AlertDialog.Builder(activity).setTitle(R.string.airplay_name_title)
                .setMessage(R.string.airplay_change_warning).setView(input)
                .setPositiveButton(R.string.ok, null).setNegativeButton(R.string.cancel, null).create();
        edit.setOnShowListener(d -> edit.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = input.getText().toString().trim();
            if (value.isEmpty() || value.chars().anyMatch(Character::isISOControl)) {
                input.setError(activity.getString(R.string.airplay_invalid_name)); return;
            }
            Bundle change = new Bundle(); change.putString("name", value); save(change); edit.dismiss();
        }));
        edit.show();
    }
    private void open(Intent intent) {
        try { activity.startActivity(intent); }
        catch (RuntimeException error) { Toast.makeText(activity, R.string.receiver_error, Toast.LENGTH_LONG).show(); }
    }
    private TextView label(String text) {
        TextView view = new TextView(activity); view.setText(text); view.setTextSize(14);
        view.setPadding(0, dp(8), 0, dp(8)); return view;
    }
    private TextView action(String text, Runnable run) {
        TextView view = label(text); view.setTextSize(16); view.setMinHeight(dp(44));
        view.setFocusable(true); view.setClickable(true); view.setOnClickListener(v -> run.run());
        view.setBackgroundResource(android.R.drawable.list_selector_background); return view;
    }
    private Switch toggle(int text) {
        Switch view = new Switch(activity); view.setText(text); view.setTextSize(16);
        view.setMinHeight(dp(48)); view.setFocusable(true); return view;
    }
    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
}
