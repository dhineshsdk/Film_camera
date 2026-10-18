package com.s24film.camera;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.MediaScannerConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Photo library: grid of everything shot into DCIM/S24Film; tap to edit in the darkroom, long-press to delete. */
public class LibraryActivity extends Activity implements AdapterView.OnItemClickListener,
        AdapterView.OnItemLongClickListener, View.OnClickListener {
    private static final File DIR = new File("/sdcard/DCIM/S24Film");
    private static final int COLS = 3;

    private final List<File> photos = new ArrayList<>();
    private final ExecutorService loader = Executors.newFixedThreadPool(2);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> cache = new LruCache<>(80);
    private GridAdapter adapter;
    private TextView count;
    private int cell;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        cell = getResources().getDisplayMetrics().widthPixels / COLS;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF000000);
        root.setPadding(0, dp(28), 0, 0);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(8), dp(4), dp(8), dp(4));
        top.addView(button("Back", 1));
        count = new TextView(this);
        count.setTextColor(Color.WHITE);
        count.setTextSize(17);
        count.setTypeface(Typeface.DEFAULT_BOLD);
        count.setGravity(Gravity.CENTER);
        count.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(count);
        top.addView(button("Gallery", 2));
        root.addView(top);

        GridView grid = new GridView(this);
        grid.setNumColumns(COLS);
        grid.setHorizontalSpacing(0);
        grid.setVerticalSpacing(0);
        adapter = new GridAdapter(this);
        grid.setAdapter(adapter);
        grid.setOnItemClickListener(this);
        grid.setOnItemLongClickListener(this);
        root.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        loader.shutdownNow();
    }

    private int dp(int v) { return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics())); }

    private Button button(String text, int tag) {
        Button t = new Button(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setAllCaps(false);
        t.setBackgroundColor(0xFF2A2A2A);
        t.setMinWidth(0); t.setMinimumWidth(0); t.setMinHeight(0); t.setMinimumHeight(0);
        t.setPadding(dp(14), 0, dp(14), 0);
        t.setTag(tag);
        t.setOnClickListener(this);
        t.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)));
        return t;
    }

    private void refresh() {
        photos.clear();
        File[] files = DIR.listFiles();
        if (files != null) {
            Arrays.sort(files, new Newest());
            for (File f : files) {
                String n = f.getName().toLowerCase();
                if (n.endsWith(".jpg") || n.endsWith(".jpeg")) photos.add(f);
            }
        }
        count.setText(photos.isEmpty() ? "No photos yet" : photos.size() + " photos");
        adapter.notifyDataSetChanged();
    }

    static class Newest implements Comparator<File> {
        @Override public int compare(File a, File b) { return Long.compare(b.lastModified(), a.lastModified()); }
    }

    @Override
    public void onClick(View v) {
        if ((Integer) v.getTag() == 1) { finish(); return; }
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage("com.sec.android.gallery3d");
            if (i != null) startActivity(i);
        } catch (Throwable t) {
            Toast.makeText(this, "Gallery unavailable", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
        Intent i = new Intent(this, EditorActivity.class);
        i.putExtra(EditorActivity.EXTRA_PATH, photos.get(pos).getAbsolutePath());
        startActivity(i);
    }

    @Override
    public boolean onItemLongClick(AdapterView<?> p, View v, int pos, long id) {
        File f = photos.get(pos);
        new AlertDialog.Builder(this)
            .setTitle("Delete " + f.getName() + "?")
            .setMessage("Removes the JPEG and its DNG (if any).")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete", new Delete(this, f))
            .show();
        return true;
    }

    static class Delete implements DialogInterface.OnClickListener {
        private final LibraryActivity a; private final File f;
        Delete(LibraryActivity a, File f) { this.a = a; this.f = f; }
        @Override public void onClick(DialogInterface d, int w) {
            String n = f.getName();
            int dot = n.lastIndexOf('.');
            File dng = new File(f.getParentFile(), (dot > 0 ? n.substring(0, dot) : n) + ".dng");
            f.delete();
            if (dng.exists()) dng.delete();
            MediaScannerConnection.scanFile(a, new String[]{f.getAbsolutePath(), dng.getAbsolutePath()}, null, null);
            a.cache.remove(f.getAbsolutePath());
            a.refresh();
        }
    }

    // ---- Grid ----------------------------------------------------------------------------------
    static class GridAdapter extends BaseAdapter {
        private final LibraryActivity a;
        GridAdapter(LibraryActivity a) { this.a = a; }
        @Override public int getCount() { return a.photos.size(); }
        @Override public Object getItem(int i) { return a.photos.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public View getView(int i, View convert, ViewGroup parent) {
            ImageView iv = (ImageView) convert;
            if (iv == null) {
                iv = new ImageView(a);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setLayoutParams(new AbsListViewLP(a.cell));
                iv.setBackgroundColor(0xFF111111);
                iv.setPadding(1, 1, 1, 1);
            }
            String path = a.photos.get(i).getAbsolutePath();
            iv.setTag(path);
            Bitmap cached = a.cache.get(path);
            if (cached != null) {
                iv.setImageBitmap(cached);
            } else {
                iv.setImageDrawable(null);
                a.loader.execute(new Thumb(a, iv, path));
            }
            return iv;
        }
    }

    static class AbsListViewLP extends android.widget.AbsListView.LayoutParams {
        AbsListViewLP(int size) { super(size, size); }
    }

    static class Thumb implements Runnable {
        private final LibraryActivity a; private final ImageView iv; private final String path;
        Thumb(LibraryActivity a, ImageView iv, String path) { this.a = a; this.iv = iv; this.path = path; }
        @Override public void run() {
            try {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(path, o);
                int s = 1;
                while (Math.min(o.outWidth, o.outHeight) / (s * 2) >= a.cell) s *= 2;
                o = new BitmapFactory.Options();
                o.inSampleSize = s;
                Bitmap b = BitmapFactory.decodeFile(path, o);
                if (b != null) {
                    a.cache.put(path, b);
                    a.ui.post(new SetThumb(iv, path, b));
                }
            } catch (Throwable ignored) { }
        }
    }

    static class SetThumb implements Runnable {
        private final ImageView iv; private final String path; private final Bitmap b;
        SetThumb(ImageView iv, String path, Bitmap b) { this.iv = iv; this.path = path; this.b = b; }
        @Override public void run() { if (path.equals(iv.getTag())) iv.setImageBitmap(b); }
    }
}
