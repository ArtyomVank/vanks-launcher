package os.vanks.launcher;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;

/**
 * Отдаёт другим приложениям снимок экрана для поиска по нему (зажатие
 * полоски внизу → Google Lens). Один файл в кэше, только чтение и только
 * тем, кому выдали разрешение на этот адрес.
 */
public final class ShotProvider extends ContentProvider {
    static final String AUTHORITY = "os.vanks.launcher.shot";

    private static File file(Context c) {
        return new File(c.getCacheDir(), "screen.png");
    }

    /** Сохранить снимок; вернёт адрес для Intent или null. */
    static Uri save(Context c, Bitmap shot) {
        Bitmap b = shot.getConfig() == Bitmap.Config.HARDWARE ? shot.copy(Bitmap.Config.ARGB_8888, false) : shot;
        if (b == null) return null;
        try (FileOutputStream out = new FileOutputStream(file(c))) {
            b.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (Exception e) {
            return null;
        } finally {
            if (b != shot) b.recycle();
        }
        // свой адрес на каждый снимок: принимающее приложение не возьмёт старый из кэша
        return Uri.parse("content://" + AUTHORITY + "/screen.png?t=" + System.currentTimeMillis());
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        return "image/png";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("только чтение");
        return ParcelFileDescriptor.open(file(getContext()), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        File f = file(getContext());
        String[] cols = projection != null ? projection : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor c = new MatrixCursor(cols, 1);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = "screen.png";
            else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = f.length();
        }
        c.addRow(row);
        return c;
    }

    @Override
    public Uri insert(Uri uri, ContentValues v) {
        return null;
    }

    @Override
    public int delete(Uri uri, String s, String[] a) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues v, String s, String[] a) {
        return 0;
    }
}
