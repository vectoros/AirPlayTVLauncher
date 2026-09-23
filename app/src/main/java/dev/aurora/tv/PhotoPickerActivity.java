package dev.aurora.tv;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;

/** Remote-friendly local album browser. The result contains URIs in ClipData. */
public final class PhotoPickerActivity extends Activity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Photo> all = new ArrayList<>(), visible = new ArrayList<>();
    private final LinkedHashSet<Uri> selected = new LinkedHashSet<>();
    private GridView grid;
    private TextView status;
    private Button done;
    private Spinner albums;
    private PhotoAdapter adapter;
    private volatile boolean destroyed;
    private static final int PERMISSION = 11, DOCUMENTS = 12;
    private static final class Photo {
        final Uri uri; final String album, name;
        Photo(Uri uri, String album, String name) { this.uri = uri; this.album = album; this.name = name; }
    }

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (saved != null) {
            ArrayList<String> uris = saved.getStringArrayList("selected");
            if (uris != null) for (String uri : uris) selected.add(Uri.parse(uri));
        }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(30), dp(18), dp(30), dp(18)); root.setBackgroundColor(0xff101a27);
        TextView title = new TextView(this); title.setText(getString(R.string.album_title)); title.setTextSize(25); title.setTextColor(Color.WHITE); root.addView(title);
        status = new TextView(this); status.setTextColor(0xffcbd5df); status.setTextSize(14); status.setPadding(0, dp(8), 0, dp(8)); root.addView(status);
        LinearLayout actions = new LinearLayout(this);
        done = button(getString(R.string.use_photos), () -> finishSelection(new ArrayList<>(selected))); actions.addView(done);
        actions.addView(button(getString(R.string.files_usb), this::openDocuments));
        actions.addView(button(getString(R.string.refresh_access), this::requestPhotos));
        actions.addView(button(getString(R.string.cancel), this::finish));
        HorizontalScrollView actionScroll = new HorizontalScrollView(this);
        actionScroll.setHorizontalScrollBarEnabled(false); actionScroll.addView(actions);
        root.addView(actionScroll);
        albums = new Spinner(this); root.addView(albums);
        grid = new GridView(this); grid.setNumColumns(5); grid.setVerticalSpacing(dp(8)); grid.setHorizontalSpacing(dp(8)); grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setSelector(android.R.drawable.dialog_holo_light_frame);
        adapter = new PhotoAdapter(); grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, position, id) -> {
            Uri uri = visible.get(position).uri;
            if (!selected.remove(uri)) {
                if (selected.size() >= 30) { Toast.makeText(this, getString(R.string.photo_limit), Toast.LENGTH_SHORT).show(); return; }
                selected.add(uri);
            }
            LinearLayout card = (LinearLayout) view;
            boolean checked = selected.contains(uri);
            card.setBackgroundColor(checked ? 0xff3479ae : 0xff243243);
            ((TextView) card.getChildAt(1)).setText((checked ? "✓ " : "") + visible.get(position).name);
            updateCount();
            grid.requestFocus(); grid.setSelection(position);
        });
        root.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1)); setContentView(root); requestPhotos();
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private Button button(String label, Runnable action) {
        Button b = new Button(this); b.setText(label); b.setOnClickListener(v -> action.run()); return b;
    }
    private boolean granted(String permission) { return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED; }
    private boolean hasPhotos() {
        return Build.VERSION.SDK_INT >= 33 ? granted(Manifest.permission.READ_MEDIA_IMAGES) ||
                (Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)) : granted(Manifest.permission.READ_EXTERNAL_STORAGE);
    }
    private void requestPhotos() {
        if (hasPhotos()) { loadPhotos(); return; }
        String[] permissions = Build.VERSION.SDK_INT >= 34 ? new String[]{Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED} :
                new String[]{Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_IMAGES : Manifest.permission.READ_EXTERNAL_STORAGE};
        requestPermissions(permissions, PERMISSION);
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == PERMISSION) {
            if (hasPhotos()) loadPhotos();
            else status.setText(getString(R.string.photo_permission));
        }
    }
    private void loadPhotos() {
        status.setText(getString(R.string.loading_album));
        io.execute(() -> {
            List<Photo> photos = new ArrayList<>(); String error = null;
            Uri collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            try (Cursor cursor = getContentResolver().query(collection,
                    new String[]{MediaStore.Images.Media._ID, MediaStore.Images.Media.BUCKET_DISPLAY_NAME, MediaStore.Images.Media.DISPLAY_NAME}, null, null,
                    MediaStore.Images.Media.DATE_ADDED + " DESC")) {
                if (cursor != null) while (cursor.moveToNext()) {
                    String album = cursor.getString(1);
                    photos.add(new Photo(ContentUris.withAppendedId(collection, cursor.getLong(0)), album == null ? getString(R.string.unnamed_album) : album, cursor.getString(2)));
                }
            } catch (RuntimeException e) { error = getString(R.string.album_error); }
            String message = error;
            main.post(() -> {
                if (destroyed) return;
                all.clear(); all.addAll(photos);
                List<String> names = new ArrayList<>(); names.add(getString(R.string.all_albums));
                for (Photo photo : all) if (!names.contains(photo.album)) names.add(photo.album);
                albums.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
                albums.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                    public void onNothingSelected(AdapterView<?> parent) { }
                    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                        visible.clear(); for (Photo photo : all) if (position == 0 || photo.album.equals(names.get(position))) visible.add(photo);
                        adapter.notifyDataSetChanged();
                        if (!visible.isEmpty()) { grid.requestFocus(); grid.setSelection(0); }
                    }
                });
                updateCount();
                if (message != null) status.setText(message);
            });
        });
    }
    private void updateCount() {
        done.setText(getString(R.string.use_photos_count, selected.size())); done.setEnabled(!selected.isEmpty());
        status.setText(all.isEmpty() ? getString(R.string.album_empty) :
                getString(R.string.selection_hint, selected.size()) +
                (Build.VERSION.SDK_INT >= 34 && !granted(Manifest.permission.READ_MEDIA_IMAGES) ? getString(R.string.limited_access) : ""));
    }
    private void openDocuments() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        android.content.pm.ResolveInfo resolved = getPackageManager().resolveActivity(intent, 0);
        if (resolved == null || "com.android.tv.frameworkpackagestubs".equals(resolved.activityInfo.packageName)) {
            status.setText(getString(R.string.picker_missing)); return;
        }
        try { startActivityForResult(intent, DOCUMENTS); }
        catch (ActivityNotFoundException | SecurityException e) { status.setText(getString(R.string.picker_error)); }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != DOCUMENTS || result != RESULT_OK || data == null) return;
        LinkedHashSet<Uri> uris = new LinkedHashSet<>();
        if (data.getData() != null) uris.add(data.getData());
        if (data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount(); i++) {
            Uri uri = data.getClipData().getItemAt(i).getUri(); if (uri != null) uris.add(uri);
        }
        if (uris.size() > 30) { status.setText(getString(R.string.photo_limit_retry)); return; }
        for (Uri uri : uris) try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
        catch (SecurityException ignored) { /* Main immediately imports while the temporary grant remains active. */ }
        finishSelection(new ArrayList<>(uris));
    }
    private void finishSelection(List<Uri> uris) {
        if (uris.isEmpty()) return;
        ClipData clip = ClipData.newUri(getContentResolver(), "Wallpaper photos", uris.get(0));
        for (int i = 1; i < uris.size(); i++) clip.addItem(new ClipData.Item(uris.get(i)));
        Intent data = new Intent().setData(uris.get(0)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        data.setClipData(clip);
        setResult(RESULT_OK, data); finish();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        ArrayList<String> uris = new ArrayList<>(); for (Uri uri : selected) uris.add(uri.toString());
        state.putStringArrayList("selected", uris); super.onSaveInstanceState(state);
    }
    @Override protected void onDestroy() { destroyed = true; io.shutdownNow(); main.removeCallbacksAndMessages(null); super.onDestroy(); }

    private final class PhotoAdapter extends BaseAdapter {
        public int getCount() { return visible.size(); }
        public Object getItem(int position) { return visible.get(position); }
        public long getItemId(int position) { return position; }
        public View getView(int position, View recycled, ViewGroup parent) {
            LinearLayout card;
            ImageView image;
            TextView label;
            if (recycled == null) {
                card = new LinearLayout(PhotoPickerActivity.this); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(dp(4),dp(4),dp(4),dp(4));
                card.setLayoutParams(new android.widget.AbsListView.LayoutParams(-1, dp(122)));
                image = new ImageView(PhotoPickerActivity.this); image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                card.addView(image, new LinearLayout.LayoutParams(-1, dp(92)));
                label = new TextView(PhotoPickerActivity.this); label.setTextColor(Color.WHITE); label.setTextSize(12); label.setSingleLine(true); card.addView(label);
            } else { card = (LinearLayout) recycled; image = (ImageView) card.getChildAt(0); label = (TextView) card.getChildAt(1); }
            Photo photo = visible.get(position);
            card.setBackgroundColor(selected.contains(photo.uri) ? 0xff3479ae : 0xff243243);
            label.setText((selected.contains(photo.uri) ? "✓ " : "") + photo.name);
            Object previous = image.getTag();
            if (previous instanceof java.util.concurrent.atomic.AtomicBoolean) ((java.util.concurrent.atomic.AtomicBoolean) previous).set(false);
            java.util.concurrent.atomic.AtomicBoolean token = new java.util.concurrent.atomic.AtomicBoolean(true);
            image.setTag(token); image.setImageDrawable(null);
            io.execute(() -> {
                if (destroyed || !token.get()) return;
                Bitmap bitmap = null;
                try { bitmap = PhotoStore.decode(PhotoPickerActivity.this, photo.uri, 320, 180); }
                catch (Exception | OutOfMemoryError ignored) { }
                Bitmap result = bitmap;
                main.post(() -> {
                    if (destroyed || image.getTag() != token) { if (result != null) result.recycle(); return; }
                    image.setImageBitmap(result);
                });
            });
            return card;
        }
    }
}
