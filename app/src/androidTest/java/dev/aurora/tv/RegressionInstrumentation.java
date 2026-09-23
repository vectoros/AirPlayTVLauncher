package dev.aurora.tv;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.*;
import android.graphics.*;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.*;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Zero-dependency device regression suite. Never touches the user's album or preferences. */
public final class RegressionInstrumentation extends Instrumentation {
    private final StringBuilder report = new StringBuilder();
    private IsolatedContext sandbox;
    private int checks;

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        Bundle results = new Bundle();
        int result = Activity.RESULT_OK;
        try {
            sandbox = new IsolatedContext(getTargetContext());
            testPhotos();
            testLanguages();
            Throwable[] schedulingFailure = new Throwable[1];
            runOnMainSync(() -> {
                try { testWallpaperScheduling(); }
                catch (Throwable error) { schedulingFailure[0] = error; }
            });
            if (schedulingFailure[0] != null) throw new AssertionError(schedulingFailure[0]);
            report.append("PASS: ").append(checks).append(" regression checks\n");
        } catch (Throwable failure) {
            result = Activity.RESULT_CANCELED;
            StringWriter trace = new StringWriter(); failure.printStackTrace(new PrintWriter(trace));
            report.append("FAIL: ").append(trace);
        } finally {
            if (sandbox != null) sandbox.cleanup();
        }
        results.putString("stream", "\n" + report);
        results.putInt("checks", checks);
        finish(result, results);
    }

    private void testPhotos() throws Exception {
        File wide = original("wide.jpg", 2400, 1400, false);
        File turned = original("turned.jpg", 400, 200, true);
        byte[] wideHash = hash(wide), turnedHash = hash(turned);
        Uri wideUri = Uri.fromFile(wide), turnedUri = Uri.fromFile(turned);
        Outcome imported = importPhotos(Arrays.asList(wideUri, turnedUri, wideUri));
        expect(imported.error == null && imported.count == 2, "Multi-photo import deduplicates URIs");
        List<File> files = PhotoStore.listFiles(sandbox);
        expect(files.size() == 2 && imported.mainThread, "Import callback is on main thread and two copies are listed");
        Bitmap wideCopy = BitmapFactory.decodeFile(files.get(0).getAbsolutePath());
        expect(wideCopy != null && wideCopy.getWidth() <= 1920 && wideCopy.getHeight() <= 1080 &&
                Math.abs((float) wideCopy.getWidth() / wideCopy.getHeight() - 2400f / 1400) < .01,
                "Large photo stays within 1920x1080 and preserves aspect ratio");
        wideCopy.recycle();
        Bitmap turnedCopy = BitmapFactory.decodeFile(files.get(1).getAbsolutePath());
        expect(turnedCopy != null && turnedCopy.getWidth() == 200 && turnedCopy.getHeight() == 400,
                "Exif rotation normalizes dimensions without upscaling");
        int top = turnedCopy.getPixel(100, 60), bottom = turnedCopy.getPixel(100, 340);
        expect(Color.red(top) > 200 && Color.blue(top) < 50 && Color.blue(bottom) > 200 && Color.red(bottom) < 50,
                "Exif 90-degree rotation normalizes actual pixel orientation");
        turnedCopy.recycle();

        File broken = new File(sandbox.root, "broken.jpg");
        try (FileOutputStream out = new FileOutputStream(broken)) { out.write(new byte[]{1, 2, 3, 4}); }
        Outcome failed = importPhotos(Arrays.asList(wideUri, Uri.fromFile(broken)));
        expect(failed.error != null && files.equals(PhotoStore.listFiles(sandbox)), "Corrupt batch preserves the previous album");
        File[] onDisk = new File(sandbox.getFilesDir(), "wallpapers").listFiles();
        expect(onDisk != null && onDisk.length == 2 && Arrays.asList(onDisk).containsAll(files),
                "Failed batch deletes only its temporary copies");
        Outcome empty = importPhotos(Collections.emptyList());
        expect(empty.error == null && empty.count == 2 && files.equals(PhotoStore.listFiles(sandbox)),
                "Empty selection leaves previous album unchanged");
        List<Uri> tooMany = new ArrayList<>();
        for (int i = 0; i < 31; i++) tooMany.add(Uri.fromFile(new File(sandbox.root, "unused-" + i + ".jpg")));
        expect(importPhotos(tooMany).error != null && files.equals(PhotoStore.listFiles(sandbox)),
                "More than 30 unique photos are rejected without changing album");
        Outcome replacement = importPhotos(Collections.singletonList(turnedUri));
        expect(replacement.error == null && replacement.count == 1 && !files.get(0).exists() && !files.get(1).exists(),
                "Successful replacement removes previous app-owned copies");
        PhotoStore.clear(sandbox);
        expect(PhotoStore.listFiles(sandbox).isEmpty(), "Clear removes album metadata");
        onDisk = new File(sandbox.getFilesDir(), "wallpapers").listFiles();
        expect(onDisk != null && onDisk.length == 0 && Arrays.equals(wideHash, hash(wide)) && Arrays.equals(turnedHash, hash(turned)),
                "Clear removes app copies and leaves original photo bytes unchanged");
    }

    private void testLanguages() throws Exception {
        android.content.res.Configuration config = new android.content.res.Configuration(getTargetContext().getResources().getConfiguration());
        config.setLocales(new LocaleList(Locale.US));
        Context en = getTargetContext().createConfigurationContext(config);
        config.setLocales(new LocaleList(Locale.SIMPLIFIED_CHINESE));
        Context zh = getTargetContext().createConfigurationContext(config);
        config.setLocales(new LocaleList(Locale.FRENCH));
        Context fallback = getTargetContext().createConfigurationContext(config);
        expect("All apps".equals(en.getString(R.string.all_apps)), "English home resources resolve");
        expect(!en.getString(R.string.all_apps).equals(zh.getString(R.string.all_apps)), "Chinese uses translated resources");
        expect(en.getString(R.string.all_apps).equals(fallback.getString(R.string.all_apps)), "Unsupported language falls back to English");
        expect("Selected 1 photo".equals(en.getResources().getQuantityString(R.plurals.selected_photos, 1, 1)), "English singular photo count");
        expect("Selected 2 photos".equals(en.getResources().getQuantityString(R.plurals.selected_photos, 2, 2)), "English plural photo count");
        expect(!WeatherService.describe(en, 95).equals(WeatherService.describe(zh, 95)), "Weather codes localize at render time");
        org.json.JSONObject cached = new org.json.JSONObject()
                .put("city", "Test City").put("date", "2026-09-24")
                .put("temperature", 20).put("high", 24).put("low", 16)
                .put("code", 0).put("updatedAt", 1).put("locationSource", "IP test");
        WeatherService.Weather weather = WeatherService.Weather.fromJson(cached);
        expect(weather.sourceText(en).equals(en.getString(R.string.location_ip)), "Legacy cached IP label resolves in English");
        expect(weather.sourceText(zh).equals(zh.getString(R.string.location_ip)), "Same cache resolves in Chinese without refetch");
        expect("High 24°  Low 16°".equals(weather.rangeText(en)), "Weather range uses localized format arguments");
    }

    /** Exercise hold/transition boundaries without waiting for a real slideshow. */
    private void testWallpaperScheduling() throws Exception {
        WallpaperView view = new WallpaperView(sandbox);
        Bitmap first = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
        Bitmap next = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
        try {
            setField(view, "photoMode", true);
            setField(view, "currentPhoto", first);
            setField(view, "nextPhoto", next);
            setField(view, "effect", 0);
            setField(view, "intervalSeconds", 15);
            java.lang.reflect.Method delay = WallpaperView.class.getDeclaredMethod("nextFrameDelay");
            java.lang.reflect.Method advance = WallpaperView.class.getDeclaredMethod("advancePhotos", long.class);
            delay.setAccessible(true); advance.setAccessible(true);
            expect((long) delay.invoke(view) == 1000, "Static photo uses slow polling");
            setField(view, "photoElapsed", 14980L);
            expect((long) delay.invoke(view) == 20, "Hold timer preserves the transition deadline");
            advance.invoke(view, 20L);
            expect((long) getField(view, "transitionElapsed") == 0, "Idle tick does not skip transition start");
            expect((long) delay.invoke(view) == 40, "Transition returns to animation cadence");
            advance.invoke(view, 1800L);
            expect(getField(view, "currentPhoto") == next && !view.isTransitioning(), "Transition publishes the incoming photo");
            expect((long) delay.invoke(view) == 1000, "Finished transition returns to idle cadence");
            setField(view, "effect", 1);
            expect((long) delay.invoke(view) == 40, "Zoom stays animated even for a single photo");
            setField(view, "currentPhoto", null);
            setField(view, "effect", 0);
            expect((long) delay.invoke(view) == 40, "Procedural fallback stays animated");
        } finally {
            view.close(); first.recycle(); next.recycle();
        }
    }
    private void setField(Object target, String name, Object value) throws Exception {
        java.lang.reflect.Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true); field.set(target, value);
    }
    private Object getField(Object target, String name) throws Exception {
        java.lang.reflect.Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true); return field.get(target);
    }

    private Outcome importPhotos(List<Uri> uris) throws InterruptedException {
        Outcome result = new Outcome();
        CountDownLatch latch = new CountDownLatch(1);
        PhotoStore.importSelection(sandbox, uris, (count, error) -> {
            result.count = count; result.error = error;
            result.mainThread = Looper.myLooper() == Looper.getMainLooper(); latch.countDown();
        });
        if (!latch.await(30, TimeUnit.SECONDS)) throw new AssertionError("Import callback timed out");
        return result;
    }
    private File original(String name, int width, int height, boolean rotate) throws IOException {
        File file = new File(sandbox.root, name);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap); canvas.drawColor(Color.BLUE);
        Paint paint = new Paint(); paint.setColor(Color.RED); canvas.drawRect(0, 0, width / 2f, height, paint);
        try (FileOutputStream out = new FileOutputStream(file)) { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out); }
        finally { bitmap.recycle(); }
        if (rotate) {
            ExifInterface exif = new ExifInterface(file.getAbsolutePath());
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, String.valueOf(ExifInterface.ORIENTATION_ROTATE_90)); exif.saveAttributes();
        }
        return file;
    }
    private void expect(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
        checks++; report.append("PASS ").append(description).append('\n');
    }
    private byte[] hash(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[8192]; int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        return digest.digest();
    }
    private static final class Outcome { int count; String error; boolean mainThread; }
    private static final class IsolatedContext extends ContextWrapper {
        final File root;
        final String preferencePrefix = "photo-regression-" + UUID.randomUUID() + "-";
        IsolatedContext(Context base) throws IOException {
            super(base); root = new File(base.getCacheDir(), preferencePrefix);
            if (!getFilesDir().mkdirs()) throw new IOException("Cannot create isolated photo test directory");
        }
        @Override public Context getApplicationContext() { return this; }
        @Override public File getFilesDir() { return new File(root, "files"); }
        @Override public android.content.SharedPreferences getSharedPreferences(String name, int mode) {
            return getBaseContext().getSharedPreferences(preferencePrefix + name, mode);
        }
        void cleanup() { getBaseContext().deleteSharedPreferences(preferencePrefix + "photos"); delete(root); }
        private void delete(File file) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) delete(child);
            file.delete();
        }
    }
}
