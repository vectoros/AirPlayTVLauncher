package dev.aurora.tv;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Starts our separately licensed receiver without opening its settings screen. */
final class AirPlayCompanion {
    static final String PACKAGE = "dev.aurora.airplay";
    static void ensureStarted(Context context) {
        Intent intent = new Intent("io.github.jqssun.airplay.START_SERVER")
                .setComponent(new ComponentName(PACKAGE,
                        "io.github.jqssun.airplay.service.AirPlayService"));
        try {
            // Called only while the Launcher Activity is visible. The service is idempotent.
            context.startForegroundService(intent);
        } catch (RuntimeException error) {
            Log.w("AuroraAirPlay", "Receiver not installed, signed differently, or unavailable", error);
        }
    }
}
