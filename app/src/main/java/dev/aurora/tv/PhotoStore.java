package dev.aurora.tv;

import android.content.Context;
import android.graphics.*;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.*;
import org.json.JSONArray;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Only app-owned, downscaled copies are retained. Originals are never changed. */
public final class PhotoStore {
    public interface Callback { void onComplete(int count, String error); }
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static File directory(Context c) { return new File(c.getFilesDir(), "wallpapers"); }

    public static synchronized List<File> listFiles(Context c) {
        List<File> files = new ArrayList<>();
        try {
            JSONArray names = new JSONArray(c.getSharedPreferences("photos", 0).getString("files", "[]"));
            for (int i = 0; i < names.length(); i++) {
                String name = names.getString(i);
                if (!name.equals(new File(name).getName())) continue;
                File file = new File(directory(c), name);
                if (file.isFile()) files.add(file);
            }
        } catch (Exception ignored) { }
        return files;
    }

    public static void importSelection(Context context, List<Uri> selection, Callback callback) {
        Context c = context.getApplicationContext();
        List<Uri> uris = new ArrayList<>(new LinkedHashSet<>(selection));
        IO.execute(() -> {
            String error = null;
            int count = 0;
            try { count = importNow(c, uris); }
            catch (Exception | OutOfMemoryError e) { error = "照片导入失败，请确认照片可读取并重试；原相册已保留。"; }
            int result = count; String message = error;
            new Handler(Looper.getMainLooper()).post(() -> callback.onComplete(result, message));
        });
    }

    private static synchronized int importNow(Context c, List<Uri> uris) throws IOException {
        if (uris.isEmpty()) return listFiles(c).size();
        if (uris.size() > 30) throw new IOException("Maximum 30 photos");
        File dir = directory(c);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create album");
        List<File> created = new ArrayList<>();
        JSONArray names = new JSONArray();
        boolean committed = false;
        try {
            for (Uri uri : uris) {
                Bitmap bitmap = decode(c, uri, 1920, 1080);
                if (bitmap == null) throw new IOException("Unsupported photo");
                File file = new File(dir, UUID.randomUUID() + ".jpg");
                created.add(file);
                try (FileOutputStream output = new FileOutputStream(file)) {
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)) throw new IOException("Encode failed");
                    output.getFD().sync();
                } finally { bitmap.recycle(); }
                names.put(file.getName());
            }
            String previous = c.getSharedPreferences("photos", 0).getString("files", "[]");
            if (!c.getSharedPreferences("photos", 0).edit().putString("files", names.toString()).commit()) {
                c.getSharedPreferences("photos", 0).edit().putString("files", previous).commit();
                throw new IOException("Save failed");
            }
            committed = true;
            File[] old = dir.listFiles();
            if (old != null) for (File file : old) if (!created.contains(file)) file.delete();
            return created.size();
        } finally { if (!committed) for (File file : created) file.delete(); }
    }

    public static synchronized void clear(Context c) {
        if (!c.getSharedPreferences("photos", 0).edit().remove("files").commit()) return;
        File[] files = directory(c).listFiles();
        if (files != null) for (File file : files) file.delete();
    }

    static Bitmap decode(Context c, Uri uri, int width, int height) throws IOException {
        if (Build.VERSION.SDK_INT >= 28) {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(c.getContentResolver(), uri), (decoder, info, source) -> {
                float scale = Math.min(1f, Math.min((float) width / info.getSize().getWidth(), (float) height / info.getSize().getHeight()));
                decoder.setTargetSize(Math.max(1, Math.round(info.getSize().getWidth() * scale)), Math.max(1, Math.round(info.getSize().getHeight() * scale)));
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            });
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(in, null, options); }
        if (options.outWidth <= 0 || options.outHeight <= 0) throw new IOException("Invalid image");
        int orientation = 1;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in != null) orientation = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1);
        } catch (IOException ignored) { }
        options.inSampleSize = 1;
        while (options.outWidth / options.inSampleSize > width * 2 || options.outHeight / options.inSampleSize > height * 2) options.inSampleSize *= 2;
        options.inJustDecodeBounds = false;
        Bitmap bitmap;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) { bitmap = BitmapFactory.decodeStream(in, null, options); }
        if (bitmap == null) throw new IOException("Invalid image");
        Matrix matrix = new Matrix();
        switch (orientation) {
            case 2: matrix.setScale(-1, 1); break;
            case 3: matrix.setRotate(180); break;
            case 4: matrix.setRotate(180); matrix.postScale(-1, 1); break;
            case 5: matrix.setRotate(90); matrix.postScale(-1, 1); break;
            case 6: matrix.setRotate(90); break;
            case 7: matrix.setRotate(-90); matrix.postScale(-1, 1); break;
            case 8: matrix.setRotate(-90); break;
            default: break;
        }
        Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        if (rotated != bitmap) bitmap.recycle();
        float scale = Math.min(1f, Math.min((float) width / rotated.getWidth(), (float) height / rotated.getHeight()));
        Bitmap result = Bitmap.createScaledBitmap(rotated, Math.max(1, Math.round(rotated.getWidth() * scale)), Math.max(1, Math.round(rotated.getHeight() * scale)), true);
        if (result != rotated) rotated.recycle();
        return result;
    }
}
