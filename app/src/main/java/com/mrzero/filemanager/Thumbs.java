package com.mrzero.filemanager;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal async thumbnail loader. Only used for image files in the grid view,
 * so it stays deliberately small: a memory cache, a tag check to drop stale
 * requests, and a single background thread.
 */
public final class Thumbs {

    private static final ExecutorService POOL = Executors.newFixedThreadPool(2,
            new ThreadFactory() {
                final AtomicInteger n = new AtomicInteger();

                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "thumbs-" + n.incrementAndGet());
                    t.setPriority(Thread.MIN_PRIORITY);
                    return t;
                }
            });

    private static final LruCache<String, Bitmap> CACHE =
            new LruCache<String, Bitmap>(calcCacheSize()) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return value.getByteCount() / 1024;
                }
            };

    private Thumbs() {}

    private static int calcCacheSize() {
        int mb = (int) (Runtime.getRuntime().maxMemory() / (1024 * 1024));
        return Math.max(8, Math.min(48, mb / 8)) * 1024;
    }

    /**
     * The tag is the cache key, so recycled views can't show the wrong image.
     *
     * @param onFail run on the main thread when the file cannot be decoded, so the
     *               placeholder can be swapped back for the file's type icon
     */
    public static void load(ImageView view, FileEntry entry, int targetPx) {
        load(view, entry, targetPx, null);
    }

    public static void load(ImageView view, FileEntry entry, int targetPx,
            Runnable onFail) {
        if (view == null || entry == null) return;
        String key = entry.file.getAbsolutePath() + "@" + targetPx;
        view.setTag(key);

        if (entry.kind != FileEntry.Kind.IMAGE) {
            view.setImageDrawable(null);
            return;
        }
        Bitmap hit = CACHE.get(key);
        if (hit != null) {
            view.setImageBitmap(hit);
            return;
        }
        final WeakReference<ImageView> ref = new WeakReference<>(view);
        final Context ctx = view.getContext().getApplicationContext();
        final String finalKey = key;
        POOL.execute(() -> {
            Bitmap bmp = decode(ctx, entry, targetPx);
            android.os.Handler main = new android.os.Handler(
                    android.os.Looper.getMainLooper());
            if (bmp == null) {
                // Corrupt or unsupported file: let the caller put its type icon
                // back instead of leaving an empty placeholder on screen.
                main.post(() -> {
                    ImageView v = ref.get();
                    if (v != null && finalKey.equals(v.getTag()) && onFail != null) {
                        onFail.run();
                    }
                });
                return;
            }
            CACHE.put(finalKey, bmp);
            final Bitmap out = bmp;
            main.post(() -> {
                ImageView v = ref.get();
                if (v != null && finalKey.equals(v.getTag())) v.setImageBitmap(out);
            });
        });
    }

    /**
     * Drops any photo and invalidates the pending request, so a row that is
     * recycled from an image into a folder or document cannot be filled in
     * late by a decode that was already on its way.
     */
    public static void clear(ImageView view) {
        if (view == null) return;
        view.setTag(null);
        view.setImageDrawable(null);
    }

    private static Bitmap decode(Context ctx, FileEntry entry, int targetPx) {
        InputStream in = null;
        try {
            in = FileEntry.openThumbnail(ctx, entry.file);
            if (in == null) return null;
            // First pass: bounds only, so huge photos are never fully decoded.
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(in, null, bounds);
            int w = bounds.outWidth, h = bounds.outHeight;
            if (w <= 0 || h <= 0) return null;
            in.close();
            in = FileEntry.openThumbnail(ctx, entry.file);
            if (in == null) return null;

            int sample = 1;
            int longest = Math.max(w, h);
            while (longest / sample > targetPx * 2) sample *= 2;

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeStream(in, null, opts);
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) { }
        }
    }

    public static void evictAll() {
        CACHE.evictAll();
    }
}
