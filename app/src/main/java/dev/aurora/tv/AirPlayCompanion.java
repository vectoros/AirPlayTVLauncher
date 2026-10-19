package dev.aurora.tv;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Same-signature IPC. Receiver owns durable settings; all provider calls stay off the UI thread. */
final class AirPlayCompanion {
    static final String PACKAGE = "dev.aurora.airplay";
    private static final Uri CONTROL = Uri.parse("content://dev.aurora.airplay.control");
    private static final ComponentName SERVICE = new ComponentName(PACKAGE,
            "io.github.jqssun.airplay.service.AirPlayService");
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    interface Callback { void complete(State state); }
    static final class State {
        final boolean installed, compatible, enabled, requirePin, overlay, casting;
        final String name, phase;
        State(boolean installed, Bundle data, boolean failed) {
            this.installed = installed;
            compatible = data != null && data.getInt("protocol") == 1;
            enabled = compatible && data.getBoolean("enabled");
            requirePin = compatible && data.getBoolean("require_pin");
            overlay = compatible && data.getBoolean("overlay");
            casting = compatible && data.getBoolean("casting");
            name = compatible ? data.getString("name", "Aurora TV") : "Aurora TV";
            phase = failed ? "ERROR" : compatible ? data.getString("state", "STOPPED") : "UNAVAILABLE";
        }
    }

    static void ensureStarted(Context context) { request(context, null, true, null); }
    static void status(Context context, Callback callback) { request(context, null, false, callback); }
    static void configure(Context context, Bundle changes, Callback callback) {
        request(context, new Bundle(changes), true, callback);
    }
    static Intent advancedSettings() {
        return new Intent().setComponent(new ComponentName(PACKAGE, "io.github.jqssun.airplay.MainActivity"));
    }

    private static void request(Context context, Bundle changes, boolean ensure, Callback callback) {
        Context app = context.getApplicationContext();
        IO.execute(() -> {
            Bundle data = null;
            boolean installed = app.getPackageManager().getLaunchIntentForPackage(PACKAGE) != null;
            boolean failed = false;
            if (installed) try {
                data = app.getContentResolver().call(CONTROL, changes == null ? "status" : "configure", null, changes);
                if (ensure && data != null && data.getInt("protocol") == 1) {
                    if (data.getBoolean("enabled")) {
                        boolean restart = changes != null && (changes.containsKey("name") || changes.containsKey("require_pin"));
                        String action = restart ? "dev.aurora.airplay.RESTART_SERVER" : "io.github.jqssun.airplay.START_SERVER";
                        if (restart || !"RUNNING".equals(data.getString("state")))
                            app.startForegroundService(new Intent(action).setComponent(SERVICE));
                    } else if (changes != null) {
                        // Persist disabled first: a racing start / boot / sticky restart will also refuse it.
                        app.stopService(new Intent().setComponent(SERVICE));
                    }
                }
            } catch (RuntimeException error) {
                failed = true;
            }
            State state = new State(installed, data, failed);
            if (callback != null) MAIN.post(() -> callback.complete(state));
        });
    }
}
