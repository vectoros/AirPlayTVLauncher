package dev.aurora.tv;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Device apps and physical HDMI ports, using public Android APIs only. */
public final class LauncherRepository {
    private static final String TAG = "AuroraRepository";
    private static final String[] BILIBILI_PACKAGES = {
            "com.xiaodianshi.tv.yst", "com.bilibili.tv", "com.xiaodianshi.tv"
    };
    private final Context context;
    private final PackageManager packages;

    public LauncherRepository(Context context) {
        this.context = context;
        packages = context.getPackageManager();
    }

    public static final class AppEntry {
        public final String label, packageName;
        public final Intent intent;
        public final Drawable icon;
        public final boolean isBilibili, installed;
        private final int rank;

        private AppEntry(String label, String packageName, Intent intent, Drawable icon,
                         boolean isBilibili, int rank) {
            this.label = label;
            this.packageName = packageName;
            this.intent = intent;
            this.icon = icon;
            this.isBilibili = isBilibili;
            this.rank = rank;
            installed = intent != null;
        }
    }

    public static final class InputEntry {
        public final String label, inputId;
        public final boolean connected;
        public final Intent intent;

        private InputEntry(String label, String inputId, boolean connected) {
            this.label = label;
            this.inputId = inputId;
            this.connected = connected;
            // Sony's TV player requires the MIME type explicitly for passthrough URIs.
            intent = new Intent(Intent.ACTION_VIEW).setDataAndType(
                    TvContract.buildChannelUriForPassthroughInput(inputId),
                    TvContract.Channels.CONTENT_ITEM_TYPE);
        }
    }

    /** Leanback activities win over phone activities; each package appears once. */
    public List<AppEntry> apps() {
        Map<String, AppEntry> found = new LinkedHashMap<>();
        Set<String> homes = new HashSet<>();
        try {
            for (ResolveInfo info : packages.queryIntentActivities(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)) {
                homes.add(info.activityInfo.packageName);
            }
        } catch (RuntimeException error) {
            Log.w(TAG, "Cannot enumerate home apps", error);
        }
        collectApps(Intent.CATEGORY_LEANBACK_LAUNCHER, found, homes);
        collectApps(Intent.CATEGORY_LAUNCHER, found, homes);
        AppEntry featured = null;
        for (String name : BILIBILI_PACKAGES) {
            if (found.containsKey(name)) {
                featured = found.remove(name);
                break;
            }
        }
        if (featured == null) {
            featured = new AppEntry("哔哩哔哩 TV", BILIBILI_PACKAGES[0], null, null, true, 0);
        }
        List<AppEntry> result = new ArrayList<>(found.values());
        Collator collator = Collator.getInstance();
        result.sort((a, b) -> a.rank == b.rank ? collator.compare(a.label, b.label)
                : Integer.compare(a.rank, b.rank));
        result.add(0, featured);
        return result;
    }

    private void collectApps(String category, Map<String, AppEntry> found, Set<String> homes) {
        try {
            for (ResolveInfo info : packages.queryIntentActivities(
                    new Intent(Intent.ACTION_MAIN).addCategory(category), 0)) {
                String name = info.activityInfo.packageName;
                if (name.equals(context.getPackageName()) || found.containsKey(name)
                        || !info.activityInfo.exported || !info.activityInfo.enabled) continue;
                try {
                    boolean bilibili = false;
                    for (String candidate : BILIBILI_PACKAGES) bilibili |= name.equals(candidate);
                    Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(category)
                            .setComponent(new ComponentName(name, info.activityInfo.name));
                    // User apps first, system apps next, alternative home screens last.
                    int systemFlags = ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP;
                    int rank = homes.contains(name) ? 2
                            : (info.activityInfo.applicationInfo.flags & systemFlags) != 0 ? 1 : 0;
                    found.put(name, new AppEntry(bilibili ? "哔哩哔哩 TV" :
                            info.loadLabel(packages).toString(), name, intent,
                            info.loadIcon(packages), bilibili, rank));
                } catch (RuntimeException error) {
                    Log.w(TAG, "Cannot load app " + name, error);
                }
            }
        } catch (RuntimeException error) {
            Log.w(TAG, "Cannot enumerate " + category, error);
        }
    }

    /** Includes disconnected physical ports, excludes duplicate HDMI-CEC child devices. */
    public List<InputEntry> hdmiInputs() {
        List<InputEntry> result = new ArrayList<>();
        try {
            TvInputManager manager = (TvInputManager) context.getSystemService(Context.TV_INPUT_SERVICE);
            if (manager == null) return result;
            for (TvInputInfo input : manager.getTvInputList()) {
                if (input.getType() != TvInputInfo.TYPE_HDMI || input.getParentId() != null) continue;
                CharSequence label = input.loadLabel(context);
                result.add(new InputEntry(label == null ? "HDMI" : label.toString(),
                        input.getId(), manager.getInputState(input.getId())
                        == TvInputManager.INPUT_STATE_CONNECTED));
            }
            Collator collator = Collator.getInstance();
            result.sort((a, b) -> collator.compare(a.label, b.label));
        } catch (RuntimeException error) {
            Log.w(TAG, "Cannot enumerate HDMI inputs", error);
        }
        return result;
    }

    public boolean launch(AppEntry app) {
        if (!app.installed) {
            toast("请先安装哔哩哔哩 TV 版，再返回首页");
            return false;
        }
        if (start(app.intent)) return true;
        toast("暂时无法打开 " + app.label);
        return false;
    }

    public boolean launch(InputEntry input) {
        if (start(input.intent)) return true;
        toast("请在电视的输入设置中选择 " + input.label);
        return openInputSettings();
    }

    public boolean openInputSettings() {
        return start(new Intent(TvInputManager.ACTION_SETUP_INPUTS)) || openSettings();
    }

    public boolean openSettings() {
        if (start(new Intent(Settings.ACTION_SETTINGS))) return true;
        toast("暂时无法打开系统设置，请使用遥控器设置键");
        return false;
    }

    private boolean start(Intent intent) {
        if (intent == null) return false;
        try {
            context.startActivity(new Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (RuntimeException error) {
            Log.w(TAG, "Cannot start " + intent, error);
            return false;
        }
    }

    private void toast(String message) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show();
    }
}
