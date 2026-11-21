package os.vanks.launcher;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;

/**
 * «Поделиться → Delta Link»: файлы — в «Загрузки» компьютера, текст — в его
 * буфер обмена. Окно — маленькая карточка поверх приложения, пока идёт
 * отправка: доступ к чужим файлам Android даёт, только пока оно открыто.
 */
public final class LinkShareActivity extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView status;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.init(this);
        if (!Link.paired(this) || !Link.enabled(this)) {
            Toast.makeText(this, "Delta Link не настроен: Настройки Vanks → Delta Link", Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, SettingsActivity.class).putExtra("page", "link").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            finish();
            return;
        }
        Intent in = getIntent();
        ArrayList<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_SEND_MULTIPLE.equals(in.getAction())) {
            ArrayList<Uri> list = in.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) uris.addAll(list);
        } else {
            Uri u = in.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) uris.add(u);
        }
        CharSequence text = in.getCharSequenceExtra(Intent.EXTRA_TEXT);
        if (uris.isEmpty()) {
            if (text == null || text.length() == 0) {
                finish();
                return;
            }
            Link.sendText(this, text.toString(), ok -> Toast.makeText(this,
                    ok ? "В буфере обмена «" + Link.pcName(this) + "»" : "Компьютер не отвечает", Toast.LENGTH_SHORT).show());
            finish();
            return;
        }
        card();
        new Thread(() -> send(uris), "link-share").start();
    }

    private void card() {
        FrameLayout root = new FrameLayout(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(22), Ui.dp(18), Ui.dp(22), Ui.dp(18));
        box.setBackground(Views.round(0xF01C1C1E, 22));
        box.addView(Views.label(this, "Delta Link", 17, Ui.medium, 0xFFFFFFFF));
        status = Views.label(this, "Отправка на «" + Link.pcName(this) + "»…", 14, Ui.regular, 0xB3EBEBF5);
        status.setPadding(0, Ui.dp(6), 0, 0);
        box.addView(status);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        lp.leftMargin = lp.rightMargin = Ui.dp(16);
        lp.bottomMargin = Ui.dp(40);
        root.addView(box, lp);
        setContentView(root);
    }

    private void send(ArrayList<Uri> uris) {
        int ok = 0;
        for (int i = 0; i < uris.size(); i++) {
            Uri u = uris.get(i);
            String[] meta = meta(u);
            int n = i + 1;
            ui.post(() -> status.setText(uris.size() > 1 ? "Отправка " + n + " из " + uris.size() + ": " + meta[0] : "Отправка: " + meta[0]));
            try {
                long size = Long.parseLong(meta[1]);
                if (size < 0) throw new IllegalStateException("размер неизвестен");
                Link.upload(this, u, meta[0], size);
                ok++;
            } catch (Exception ignored) {
            }
        }
        int sent = ok, all = uris.size();
        ui.post(() -> {
            Toast.makeText(this, sent == all ? (all == 1 ? "Файл" : "Файлы") + " в «Загрузках» «" + Link.pcName(this) + "»"
                    : sent == 0 ? "Не отправилось: компьютер не отвечает" : "Отправлено " + sent + " из " + all, Toast.LENGTH_LONG).show();
            finish();
        });
    }

    /** Имя и размер файла (-1 — неизвестен). */
    private String[] meta(Uri u) {
        String name = u.getLastPathSegment() == null ? "file" : u.getLastPathSegment();
        long size = -1;
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                if (!c.isNull(0)) name = c.getString(0);
                if (!c.isNull(1)) size = c.getLong(1);
            }
        } catch (RuntimeException ignored) {
        }
        if (size < 0) {
            try (android.content.res.AssetFileDescriptor fd = getContentResolver().openAssetFileDescriptor(u, "r")) {
                if (fd != null) size = fd.getLength();
            } catch (Exception ignored) {
            }
        }
        return new String[]{name, String.valueOf(size)};
    }
}
