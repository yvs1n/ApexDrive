package com.apexdrive.launcher.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * The wallpaper image the user picked. It is scaled down and cropped to the screen once, when
 * picked, and kept as a small JPEG in app storage, so later launches only decode a screen-sized
 * file (off the main thread). Call both methods from a background thread.
 */
public final class WallpaperStore {

    private static final String FILE = "look_wallpaper.jpg";

    private WallpaperStore() {}

    public static boolean exists(Context context) {
        return file(context).exists();
    }

    /** Reads, scales and crops the picked image to width x height and saves it. */
    public static boolean save(Context context, Uri uri, int width, int height) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false;

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, width, height);
            Bitmap src;
            try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                src = BitmapFactory.decodeStream(in, null, opts);
            }
            if (src == null) return false;

            Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            new Canvas(out).drawBitmap(src, centerCrop(src.getWidth(), src.getHeight(), width, height),
                    new Rect(0, 0, width, height), new Paint(Paint.FILTER_BITMAP_FLAG));
            src.recycle();
            File tmp = new File(context.getFilesDir(), FILE + ".tmp");
            try (FileOutputStream os = new FileOutputStream(tmp)) {
                out.compress(Bitmap.CompressFormat.JPEG, 90, os);
            }
            out.recycle();
            return tmp.renameTo(file(context));
        } catch (Exception | OutOfMemoryError e) {
            return false;
        }
    }

    /** The saved image, or null when there is none or it can't be read. */
    public static Bitmap load(Context context) {
        File f = file(context);
        if (!f.exists()) return null;
        try {
            return BitmapFactory.decodeFile(f.getAbsolutePath());
        } catch (OutOfMemoryError e) {
            return null;
        }
    }

    /** Largest power of two that keeps the decoded image at least as big as the target. */
    static int sampleSize(int w, int h, int targetW, int targetH) {
        int sample = 1;
        while (w / (sample * 2) >= targetW && h / (sample * 2) >= targetH) sample *= 2;
        return sample;
    }

    /** The middle part of a w x h image with the target's aspect ratio. */
    static Rect centerCrop(int w, int h, int targetW, int targetH) {
        if ((long) w * targetH > (long) h * targetW) {
            int cw = (int) ((long) h * targetW / targetH);
            int left = (w - cw) / 2;
            return new Rect(left, 0, left + cw, h);
        }
        int ch = (int) ((long) w * targetH / targetW);
        int top = (h - ch) / 2;
        return new Rect(0, top, w, top + ch);
    }

    private static File file(Context context) {
        return new File(context.getFilesDir(), FILE);
    }
}
