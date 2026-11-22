package os.vanks.launcher;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ImageDecoder;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.LruCache;
import android.util.Size;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Галерея Vanks: все фото и видео телефона сеткой (новые сверху), альбомы по
 * папкам и просмотр на весь экран — листание, масштаб двумя пальцами и
 * двойным касанием, свайп вниз — закрыть, видео, «Поделиться» и «Удалить».
 * Открывается и из камеры (последний снимок) и на «Открыть с помощью».
 */
public final class GalleryActivity extends Activity {
    private static final int ASK = 1, DELETE = 2;

    static final class Media {
        long id;
        Uri uri;
        boolean video;
        String bucket = "";
        long date;
        long dur;
        String mime;
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(3);
    private final LruCache<Long, Bitmap> thumbs = new LruCache<Long, Bitmap>((int) Math.min(64L << 20, Runtime.getRuntime().maxMemory() / 6)) {
        @Override
        protected int sizeOf(Long k, Bitmap b) {
            return b.getByteCount();
        }
    };

    private final List<Media> all = new ArrayList<>();
    private final List<Media> shown = new ArrayList<>();
    private final LinkedHashMap<String, List<Media>> albums = new LinkedHashMap<>();
    private String album;          // открытый альбом (null — все)
    private boolean albumsTab;

    private FrameLayout root;
    private LinearLayout head;
    private TextView title, tabAll, tabAlbums, empty;
    private GridView grid;
    private Viewer viewer;
    private int insetTop, insetBottom;
    private Uri openOnLoad;
    private Media pendingDelete;

    private int bg() { return Look.light() ? 0xFFF4F5F8 : 0xFF0B0B0F; }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Prefs.load(this);
        Hz.apply(this);
        Ui.init(this);
        Feel.init(this);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        getWindow().setStatusBarColor(0);
        getWindow().setNavigationBarColor(0);

        root = new FrameLayout(this);
        root.setBackgroundColor(bg());

        head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setPadding(Ui.dp(20), 0, Ui.dp(20), Ui.dp(10));
        title = Views.label(this, "Галерея", 30, Ui.bold, Look.fg());
        head.addView(title);
        LinearLayout tabs = new LinearLayout(this);
        tabs.setPadding(0, Ui.dp(10), 0, 0);
        tabAll = tab("Фото и видео", () -> { album = null; albumsTab = false; refresh(); });
        tabAlbums = tab("Альбомы", () -> { album = null; albumsTab = true; refresh(); });
        tabs.addView(tabAll);
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-2, -2);
        gap.leftMargin = Ui.dp(8);
        tabs.addView(tabAlbums, gap);
        head.addView(tabs);
        root.addView(head, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        grid = new GridView(this);
        grid.setVerticalSpacing(Ui.dp(2));
        grid.setHorizontalSpacing(Ui.dp(2));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setSelector(new android.graphics.drawable.ColorDrawable(0));
        grid.setClipToPadding(false);
        grid.setVerticalScrollBarEnabled(false);
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((p, v, pos, id) -> {
            if (albumsTab && album == null) {
                album = albumKeys().get(pos);
                refresh();
            } else openViewer(pos, v);
        });
        root.addView(grid, new FrameLayout.LayoutParams(-1, -1));

        empty = Views.label(this, "", 15, Ui.regular, Look.fg2());
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(Ui.dp(32), 0, Ui.dp(32), 0);
        empty.setOnClickListener(v -> askAccess());
        root.addView(empty, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));

        viewer = new Viewer();
        viewer.setVisibility(View.GONE);
        root.addView(viewer, new FrameLayout.LayoutParams(-1, -1));

        root.setOnApplyWindowInsetsListener((v, in) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets s = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                insetTop = s.top;
                insetBottom = s.bottom;
            } else {
                insetTop = in.getSystemWindowInsetTop();
                insetBottom = in.getSystemWindowInsetBottom();
            }
            layoutInsets();
            return in;
        });
        setContentView(root);
        columns();

        Intent i = getIntent();
        if (i != null && Intent.ACTION_VIEW.equals(i.getAction())) openOnLoad = i.getData();
        if (hasAccess()) load();
        else askAccess();
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if (i != null && Intent.ACTION_VIEW.equals(i.getAction()) && i.getData() != null) {
            openOnLoad = i.getData();
            if (hasAccess()) load();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
        viewer.stopVideo();
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        columns();
        viewer.requestLayout();
        viewer.photo.invalidate();
    }

    private TextView tab(String s, Runnable go) {
        TextView t = Views.label(this, s, 14, Ui.medium, Look.fg());
        t.setPadding(Ui.dp(14), Ui.dp(7), Ui.dp(14), Ui.dp(7));
        t.setOnClickListener(v -> {
            Feel.buzz(v, Feel.TICK);
            go.run();
        });
        return t;
    }

    private void layoutInsets() {
        head.setPadding(Ui.dp(20), insetTop + Ui.dp(14), Ui.dp(20), Ui.dp(10));
        head.measure(View.MeasureSpec.makeMeasureSpec(root.getWidth() > 0 ? root.getWidth() : getResources().getDisplayMetrics().widthPixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.UNSPECIFIED);
        grid.setPadding(Ui.dp(2), head.getMeasuredHeight(), Ui.dp(2), insetBottom + Ui.dp(8));
        viewer.bar.setPadding(0, Ui.dp(10), 0, insetBottom + Ui.dp(10));
        viewer.top.setPadding(Ui.dp(16), insetTop + Ui.dp(8), Ui.dp(16), Ui.dp(8));
    }

    private void columns() {
        boolean land = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        boolean grid2 = albumsTab && album == null;
        grid.setNumColumns(grid2 ? (land ? 4 : 2) : (land ? 7 : 4));
        int side = grid2 ? Ui.dp(14) : Ui.dp(2);
        grid.setVerticalSpacing(grid2 ? Ui.dp(14) : Ui.dp(2));
        grid.setHorizontalSpacing(grid2 ? Ui.dp(14) : Ui.dp(2));
        grid.setPadding(side, grid.getPaddingTop(), side, grid.getPaddingBottom());
    }

    // ── доступ и загрузка ──────────────────────────────────────────────────

    private String[] perms() {
        return Build.VERSION.SDK_INT >= 33
                ? new String[]{Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO}
                : new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};
    }

    private boolean hasAccess() {
        for (String p : perms()) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        return true;
    }

    private void askAccess() {
        if (hasAccess()) {
            load();
            return;
        }
        empty.setText("Галерее нужен доступ к фото и видео.\nКоснитесь, чтобы разрешить");
        empty.setVisibility(View.VISIBLE);
        requestPermissions(perms(), ASK);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] res) {
        super.onRequestPermissionsResult(code, p, res);
        if (code == ASK && hasAccess()) load();
    }

    private void load() {
        empty.setText("");
        io.execute(() -> {
            List<Media> list = query();
            ui.post(() -> {
                all.clear();
                all.addAll(list);
                albums.clear();
                for (Media m : all) {
                    List<Media> l = albums.get(m.bucket);
                    if (l == null) albums.put(m.bucket, l = new ArrayList<>());
                    l.add(m);
                }
                refresh();
                if (openOnLoad != null) {
                    Uri u = openOnLoad;
                    openOnLoad = null;
                    openUri(u);
                }
            });
        });
    }

    private List<Media> query() {
        ArrayList<Media> out = new ArrayList<>();
        ContentResolver cr = getContentResolver();
        Uri files = MediaStore.Files.getContentUri("external");
        String[] cols = {MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.MEDIA_TYPE,
                MediaStore.MediaColumns.BUCKET_DISPLAY_NAME, MediaStore.MediaColumns.DATE_ADDED,
                MediaStore.MediaColumns.DURATION, MediaStore.MediaColumns.MIME_TYPE};
        String sel = MediaStore.Files.FileColumns.MEDIA_TYPE + " IN ("
                + MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE + "," + MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO + ")";
        try (Cursor c = cr.query(files, cols, sel, null, MediaStore.MediaColumns.DATE_ADDED + " DESC")) {
            if (c == null) return out;
            while (c.moveToNext()) {
                Media m = new Media();
                m.id = c.getLong(0);
                m.video = c.getInt(1) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO;
                m.bucket = c.isNull(2) ? "Прочее" : c.getString(2);
                m.date = c.getLong(3);
                m.dur = c.isNull(4) ? 0 : c.getLong(4);
                m.mime = c.getString(5);
                m.uri = ContentUris.withAppendedId(m.video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                        : MediaStore.Images.Media.EXTERNAL_CONTENT_URI, m.id);
                out.add(m);
            }
        } catch (RuntimeException ignored) {
        }
        return out;
    }

    private List<String> albumKeys() {
        return new ArrayList<>(albums.keySet());
    }

    private void refresh() {
        shown.clear();
        if (album != null) {
            List<Media> l = albums.get(album);
            if (l != null) shown.addAll(l);
        } else if (!albumsTab) shown.addAll(all);
        title.setText(album != null ? album : "Галерея");
        pick(tabAll, !albumsTab);
        pick(tabAlbums, albumsTab);
        columns();
        adapter.notifyDataSetChanged();
        grid.setSelection(0);
        boolean none = albumsTab && album == null ? albums.isEmpty() : shown.isEmpty();
        if (hasAccess()) empty.setText(none ? "Здесь пока пусто" : "");
        empty.setVisibility(none || !hasAccess() ? View.VISIBLE : View.GONE);
    }

    private void pick(TextView t, boolean on) {
        t.setBackground(on ? Views.round(Ui.alpha(Ui.accent(), 0.9f), 16) : Views.round(Ui.alpha(Look.fg(), 0.08f), 16));
        t.setTextColor(on ? 0xFFFFFFFF : Look.fg());
    }

    // ── сетка ─────────────────────────────────────────────────────────────

    private final BaseAdapter adapter = new BaseAdapter() {
        @Override
        public int getCount() {
            return albumsTab && album == null ? albums.size() : shown.size();
        }

        @Override
        public Object getItem(int i) {
            return null;
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int i, View v, ViewGroup parent) {
            Cell c = v instanceof Cell ? (Cell) v : new Cell();
            if (albumsTab && album == null) {
                String k = albumKeys().get(i);
                List<Media> l = albums.get(k);
                c.bind(l.get(0), k, l.size());
            } else c.bind(shown.get(i), null, 0);
            return c;
        }
    };

    /** Квадрат с превью; у альбома — подпись и число под ним. */
    private final class Cell extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint t1 = Ui.text(Ui.medium, 14, Look.fg()), t2 = Ui.text(Ui.regular, 12, Look.fg2());
        private final Paint badge = Ui.text(Ui.medium, 11, 0xFFFFFFFF);
        private final RectF r = new RectF();
        Media m;
        String name;
        int count;

        Cell() {
            super(GalleryActivity.this);
            badge.setTextAlign(Paint.Align.RIGHT);
            badge.setShadowLayer(Ui.dpf(3), 0, 0, 0x99000000);
        }

        void bind(Media m, String name, int count) {
            this.m = m;
            this.name = name;
            this.count = count;
            invalidate();
        }

        @Override
        protected void onMeasure(int ws, int hs) {
            int w = MeasureSpec.getSize(ws);
            setMeasuredDimension(w, name != null ? w + Ui.dp(44) : w);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth();
            r.set(0, 0, w, w);
            float rad = name != null ? Ui.dpf(16) : 0;
            Bitmap b = m == null ? null : thumb(m, this);
            if (b != null) {
                // кисть могла остаться от заглушки (6 % непрозрачности) — фото было бы тусклым
                p.setAlpha(255);
                Ui.drawCover(c, b, r, rad, p, 1f);
            } else {
                p.setColor(Ui.alpha(Look.fg(), 0.06f));
                c.drawRoundRect(r, rad, rad, p);
            }
            if (m != null && m.video && name == null) {
                long s = m.dur / 1000;
                c.drawText(String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60), w - Ui.dpf(6), w - Ui.dpf(7), badge);
                Glyph.play(c, Ui.dpf(14), w - Ui.dpf(12), Ui.dpf(12), 0xFFFFFFFF, false);
            }
            if (name != null) {
                c.drawText(name, Ui.dpf(4), w + Ui.dpf(20), t1);
                c.drawText(String.valueOf(count), Ui.dpf(4), w + Ui.dpf(37), t2);
            }
        }
    }

    /** Превью из кэша; нет — загрузить в фоне и перерисовать ячейку. */
    private Bitmap thumb(Media m, View cell) {
        Bitmap b = thumbs.get(m.id);
        if (b != null) return b;
        int px = Ui.dp(140);
        io.execute(() -> {
            if (thumbs.get(m.id) != null) return;
            Bitmap t = null;
            try {
                if (Build.VERSION.SDK_INT >= 29) t = getContentResolver().loadThumbnail(m.uri, new Size(px, px), null);
                else if (!m.video) t = decode(m.uri, px);
            } catch (Exception ignored) {
            }
            if (t == null) return;
            thumbs.put(m.id, t);
            ui.post(cell::invalidate);
        });
        return null;
    }

    private Bitmap decode(Uri u, int max) {
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                ImageDecoder.Source s = ImageDecoder.createSource(getContentResolver(), u);
                return ImageDecoder.decodeBitmap(s, (d, info, src) -> {
                    int w = info.getSize().getWidth(), h = info.getSize().getHeight();
                    float k = Math.min(1f, max / (float) Math.max(w, h));
                    d.setTargetSize(Math.max(1, Math.round(w * k)), Math.max(1, Math.round(h * k)));
                    d.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                });
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 2;
            try (InputStream in = getContentResolver().openInputStream(u)) {
                return BitmapFactory.decodeStream(in, null, o);
            }
        } catch (Exception e) {
            return null;
        }
    }

    // ── просмотр ──────────────────────────────────────────────────────────

    private void openViewer(int pos, View from) {
        if (pos < 0 || pos >= shown.size()) return;
        int[] at = new int[2];
        RectF src = null;
        if (from != null) {
            from.getLocationInWindow(at);
            src = new RectF(at[0], at[1], at[0] + from.getWidth(), at[1] + from.getWidth());
        }
        viewer.open(pos, src);
    }

    private void openUri(Uri u) {
        long id = -1;
        try { id = ContentUris.parseId(u); } catch (RuntimeException ignored) {}
        for (int i = 0; i < shown.size(); i++) {
            if (shown.get(i).id == id) {
                openViewer(i, null);
                return;
            }
        }
        // не из MediaStore (например, файл из другого приложения) — только он
        Media m = new Media();
        m.id = -2;
        m.uri = u;
        String type = getIntent() != null ? getIntent().getType() : null;
        if (type == null) type = getContentResolver().getType(u);
        m.video = type != null && type.startsWith("video");
        shown.clear();
        shown.add(m);
        adapter.notifyDataSetChanged();
        openViewer(0, null);
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_BACK) {
            if (viewer.getVisibility() == View.VISIBLE) {
                viewer.close();
                return true;
            }
            if (album != null) {
                album = null;
                refresh();
                return true;
            }
        }
        return super.onKeyDown(code, e);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == DELETE && res == RESULT_OK && pendingDelete != null) removed(pendingDelete);
        pendingDelete = null;
    }

    private void share(Media m) {
        Intent s = new Intent(Intent.ACTION_SEND).setType(m.video ? "video/*" : "image/*")
                .putExtra(Intent.EXTRA_STREAM, m.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(s, "Поделиться"));
        } catch (RuntimeException e) {
            Toast.makeText(this, "Не удалось поделиться", Toast.LENGTH_SHORT).show();
        }
    }

    /** Удалить: Android сам спрашивает «Удалить?» (Android 11+), мы ждём ответа. */
    private void delete(Media m) {
        if (m.id < 0) return;
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                PendingIntent pi = MediaStore.createTrashRequest(getContentResolver(), java.util.Collections.singletonList(m.uri), true);
                pendingDelete = m;
                startIntentSenderForResult(pi.getIntentSender(), DELETE, null, 0, 0, 0);
            } else if (getContentResolver().delete(m.uri, null, null) > 0) removed(m);
        } catch (Exception e) {
            pendingDelete = null;
            Toast.makeText(this, "Не удалось удалить", Toast.LENGTH_SHORT).show();
        }
    }

    private void removed(Media m) {
        all.remove(m);
        List<Media> l = albums.get(m.bucket);
        if (l != null) {
            l.remove(m);
            if (l.isEmpty()) albums.remove(m.bucket);
        }
        int at = shown.indexOf(m);
        shown.remove(m);
        adapter.notifyDataSetChanged();
        Feel.sound(this, "swoosh");
        if (shown.isEmpty()) viewer.close();
        else viewer.show(Math.min(at, shown.size() - 1));
    }

    /** Окно просмотра: фото на весь экран, полоска сверху и кнопки снизу. */
    private final class Viewer extends FrameLayout {
        final Photo photo;
        final LinearLayout bar, top;
        final TextView when;
        VideoView video;
        boolean chrome = true;
        int pos;

        Viewer() {
            super(GalleryActivity.this);
            setBackgroundColor(0xFF000000);
            photo = new Photo();
            addView(photo, new LayoutParams(-1, -1));

            top = new LinearLayout(getContext());
            top.setGravity(Gravity.CENTER_VERTICAL);
            top.setBackground(new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0x99000000, 0}));
            View back = Views.drawn(getContext(), (cv, w, h) -> {
                Paint q = new Paint(Paint.ANTI_ALIAS_FLAG);
                q.setStyle(Paint.Style.STROKE);
                q.setStrokeWidth(Ui.dpf(2));
                q.setStrokeCap(Paint.Cap.ROUND);
                q.setColor(0xFFFFFFFF);
                float s = Ui.dpf(7);
                cv.drawLine(w / 2 + s * 0.4f, h / 2 - s, w / 2 - s * 0.6f, h / 2, q);
                cv.drawLine(w / 2 - s * 0.6f, h / 2, w / 2 + s * 0.4f, h / 2 + s, q);
            });
            back.setOnClickListener(v -> close());
            top.addView(back, new LinearLayout.LayoutParams(Ui.dp(40), Ui.dp(40)));
            when = Views.label(getContext(), "", 15, Ui.medium, 0xFFFFFFFF);
            when.setPadding(Ui.dp(8), 0, 0, 0);
            top.addView(when);
            addView(top, new LayoutParams(-1, -2, Gravity.TOP));

            bar = new LinearLayout(getContext());
            bar.setGravity(Gravity.CENTER);
            bar.setBackground(new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
                    new int[]{0x99000000, 0}));
            bar.addView(button("Поделиться", () -> { if (cur() != null) share(cur()); }));
            bar.addView(button("Удалить", () -> { if (cur() != null) delete(cur()); }));
            addView(bar, new LayoutParams(-1, -2, Gravity.BOTTOM));
        }

        private TextView button(String s, Runnable go) {
            TextView t = Views.label(getContext(), s, 14, Ui.medium, 0xFFFFFFFF);
            t.setGravity(Gravity.CENTER);
            t.setPadding(Ui.dp(22), Ui.dp(10), Ui.dp(22), Ui.dp(10));
            t.setBackground(Views.round(0x33FFFFFF, 20));
            t.setOnClickListener(v -> {
                Feel.buzz(v, Feel.TICK);
                go.run();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.leftMargin = lp.rightMargin = Ui.dp(8);
            t.setLayoutParams(lp);
            return t;
        }

        Media cur() {
            return pos >= 0 && pos < shown.size() ? shown.get(pos) : null;
        }

        void open(int p, RectF from) {
            setVisibility(View.VISIBLE);
            setAlpha(0);
            animate().alpha(1).setDuration(160).start();
            photo.enter(from);
            show(p);
            setChrome(true);
        }

        void show(int p) {
            stopVideo();
            pos = p;
            photo.reset();
            Media m = cur();
            if (m == null) return;
            when.setText(m.date > 0 ? new java.text.SimpleDateFormat("d MMMM yyyy, HH:mm", new Locale("ru")).format(new java.util.Date(m.date * 1000)) : "");
            photo.load(p);
        }

        void close() {
            stopVideo();
            animate().alpha(0).setDuration(160).withEndAction(() -> {
                setVisibility(View.GONE);
                setAlpha(1);
                photo.cur = photo.prev = photo.next = null;
            }).start();
        }

        void setChrome(boolean on) {
            chrome = on;
            top.animate().alpha(on ? 1 : 0).setDuration(160).start();
            bar.animate().alpha(on ? 1 : 0).setDuration(160).start();
            top.setClickable(on);
            for (int i = 0; i < bar.getChildCount(); i++) bar.getChildAt(i).setEnabled(on);
        }

        void playVideo() {
            Media m = cur();
            if (m == null || !m.video) return;
            stopVideo();
            video = new VideoView(getContext());
            MediaController mc = new MediaController(getContext());
            mc.setAnchorView(video);
            video.setMediaController(mc);
            video.setVideoURI(m.uri);
            video.setOnPreparedListener(mp -> video.start());
            video.setOnCompletionListener(mp -> stopVideo());
            addView(video, 1, new LayoutParams(-1, -1, Gravity.CENTER));
            setChrome(false);
        }

        void stopVideo() {
            if (video == null) return;
            try { video.stopPlayback(); } catch (RuntimeException ignored) {}
            removeView(video);
            video = null;
        }
    }

    /** Само фото: листание (соседние видны при сдвиге), масштаб, свайп вниз. */
    private final class Photo extends View {
        Bitmap cur, prev, next;
        private int loadGen;
        private float scale = 1, tx, ty, dx, dy;   // масштаб и сдвиг; dx/dy — пальцем при scale 1
        private boolean swiping, vertical;
        private RectF from;
        private long enterAt;
        private final Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final ScaleGestureDetector sg;
        private final GestureDetector gd;
        private android.animation.ValueAnimator anim;

        Photo() {
            super(GalleryActivity.this);
            sg = new ScaleGestureDetector(getContext(), new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScale(ScaleGestureDetector d) {
                    float ns = Math.max(1f, Math.min(6f, scale * d.getScaleFactor()));
                    float k = ns / scale;
                    float fx = d.getFocusX() - getWidth() / 2f, fy = d.getFocusY() - getHeight() / 2f;
                    tx = fx - (fx - tx) * k;
                    ty = fy - (fy - ty) * k;
                    scale = ns;
                    clampPan();
                    invalidate();
                    return true;
                }
            });
            gd = new GestureDetector(getContext(), new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onSingleTapConfirmed(MotionEvent e) {
                    Media m = viewer.cur();
                    float cx = getWidth() / 2f, cy = getHeight() / 2f;
                    if (m != null && m.video && scale == 1 && Math.hypot(e.getX() - cx, e.getY() - cy) < Ui.dpf(48)) {
                        viewer.playVideo();
                        return true;
                    }
                    viewer.setChrome(!viewer.chrome);
                    return true;
                }

                @Override
                public boolean onDoubleTap(MotionEvent e) {
                    float to = scale > 1.05f ? 1f : 2.5f;
                    float fx = e.getX() - getWidth() / 2f, fy = e.getY() - getHeight() / 2f;
                    float s0 = scale, x0 = tx, y0 = ty;
                    float x1 = to == 1 ? 0 : fx - (fx - tx) * to / scale, y1 = to == 1 ? 0 : fy - (fy - ty) * to / scale;
                    run(0, 1, 260, t -> {
                        scale = s0 + (to - s0) * t;
                        tx = x0 + (x1 - x0) * t;
                        ty = y0 + (y1 - y0) * t;
                        clampPan();
                        invalidate();
                    }, null);
                    return true;
                }

                @Override
                public boolean onScroll(MotionEvent a, MotionEvent b, float sx, float sy) {
                    if (scale > 1.01f && b.getPointerCount() == 1) {
                        tx -= sx;
                        ty -= sy;
                        clampPan();
                        invalidate();
                    }
                    return true;
                }
            });
        }

        void enter(RectF r0) {
            from = r0;
            enterAt = android.os.SystemClock.uptimeMillis();
        }

        void reset() {
            if (anim != null) anim.cancel();
            scale = 1;
            tx = ty = dx = dy = 0;
            viewer.setBackgroundColor(0xFF000000);
        }

        void load(int at) {
            final int gen = ++loadGen;
            cur = thumbAt(at);
            prev = thumbAt(at - 1);
            next = thumbAt(at + 1);
            invalidate();
            int max = Math.max(getResources().getDisplayMetrics().widthPixels, getResources().getDisplayMetrics().heightPixels) * 2;
            Media m = viewer.cur();
            if (m == null) return;
            io.execute(() -> {
                Bitmap full = m.video ? null : decode(m.uri, Math.min(max, 4096));
                if (m.video && Build.VERSION.SDK_INT >= 29) {
                    try { full = getContentResolver().loadThumbnail(m.uri, new Size(max / 2, max / 2), null); } catch (Exception ignored) {}
                }
                Bitmap f = full;
                ui.post(() -> {
                    if (gen != loadGen || f == null) return;
                    cur = f;
                    invalidate();
                });
            });
        }

        private Bitmap thumbAt(int i) {
            if (i < 0 || i >= shown.size()) return null;
            Media m = shown.get(i);
            Bitmap b = thumbs.get(m.id);
            if (b == null) thumb(m, this);
            return b;
        }

        private void fit(Bitmap b, float cx, float cy, float s, RectF out) {
            float k = Math.min(getWidth() / (float) b.getWidth(), getHeight() / (float) b.getHeight()) * s;
            float w = b.getWidth() * k, h = b.getHeight() * k;
            out.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
        }

        private void clampPan() {
            if (cur == null) return;
            fit(cur, 0, 0, scale, r);
            float mx = Math.max(0, (r.width() - getWidth()) / 2), my = Math.max(0, (r.height() - getHeight()) / 2);
            tx = Math.max(-mx, Math.min(mx, tx));
            ty = Math.max(-my, Math.min(my, ty));
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), cx = w / 2 + tx + dx, cy = h / 2 + ty + dy;
            float pull = Math.max(0, dy) / h;
            float s = scale * (1 - pull * 0.35f);
            float t = Ui.clamp((android.os.SystemClock.uptimeMillis() - enterAt) / 260f);
            if (cur != null) {
                fit(cur, cx, cy, s, r);
                if (from != null && t < 1) {
                    float e = Anim.EMPH.getInterpolation(t);
                    r.set(from.left + (r.left - from.left) * e, from.top + (r.top - from.top) * e,
                            from.right + (r.right - from.right) * e, from.bottom + (r.bottom - from.bottom) * e);
                    postInvalidateOnAnimation();
                }
                c.drawBitmap(cur, null, r, p);
            }
            if (scale <= 1.01f && dx != 0) {
                float gap = Ui.dpf(24);
                if (dx > 0 && prev != null) { fit(prev, cx - w - gap, h / 2, 1, r); c.drawBitmap(prev, null, r, p); }
                if (dx < 0 && next != null) { fit(next, cx + w + gap, h / 2, 1, r); c.drawBitmap(next, null, r, p); }
            }
            Media m = viewer.cur();
            if (m != null && m.video && scale <= 1.01f && viewer.video == null) {
                Paint q = new Paint(Paint.ANTI_ALIAS_FLAG);
                q.setColor(0x66000000);
                c.drawCircle(cx, cy, Ui.dpf(34), q);
                Glyph.play(c, cx + Ui.dpf(2), cy, Ui.dpf(28), 0xFFFFFFFF, false);
            }
            viewer.setBackgroundColor(Ui.alpha(0xFF000000, 1 - pull * 1.4f));
        }

        private float x0, y0;

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (viewer.video != null) return false;
            sg.onTouchEvent(e);
            gd.onTouchEvent(e);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (anim != null) anim.cancel();
                    x0 = e.getX();
                    y0 = e.getY();
                    swiping = vertical = false;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (scale > 1.01f || e.getPointerCount() > 1 || sg.isInProgress()) break;
                    float mx = e.getX() - x0, my = e.getY() - y0;
                    if (!swiping && Math.hypot(mx, my) > Ui.dpf(8)) {
                        swiping = true;
                        vertical = Math.abs(my) > Math.abs(mx) && my > 0;
                    }
                    if (swiping) {
                        if (vertical) { dy = my; dx = mx * 0.5f; }
                        else dx = mx;
                        invalidate();
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (!swiping) break;
                    swiping = false;
                    float w = getWidth();
                    if (vertical) {
                        if (dy > getHeight() * 0.18f) viewer.close();
                        else settle(0, 0, null);
                    } else if (dx < -w * 0.22f && viewer.pos < shown.size() - 1) {
                        settle(-w - Ui.dpf(24), 0, () -> viewer.show(viewer.pos + 1));
                    } else if (dx > w * 0.22f && viewer.pos > 0) {
                        settle(w + Ui.dpf(24), 0, () -> viewer.show(viewer.pos - 1));
                    } else settle(0, 0, null);
                    break;
            }
            return true;
        }

        private void settle(float toX, float toY, Runnable end) {
            float sx = dx, sy = dy;
            run(0, 1, 240, t -> {
                dx = sx + (toX - sx) * t;
                dy = sy + (toY - sy) * t;
                invalidate();
            }, () -> {
                dx = dy = 0;
                if (end != null) end.run();
                invalidate();
            });
        }

        private void run(float a, float b, long ms, java.util.function.Consumer<Float> step, Runnable end) {
            if (anim != null) anim.cancel();
            anim = Anim.run(a, b, ms, Anim.EMPH, step, end);
        }
    }
}
