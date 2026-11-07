package os.vanks.launcher;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.LauncherApps;
import android.content.pm.ShortcutInfo;
import android.os.Bundle;
import android.widget.Toast;

/**
 * Приложение просит «Добавить на главный экран» (Winlator — игру, браузер —
 * сайт). Такая просьба приходит главному экрану; Vanks её принимает: игры
 * из эмуляторов встают в «Игры Vanks», остальное — туда же, в «Ярлыки».
 * Окна у этого экрана нет.
 */
public final class PinActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LauncherApps la = getSystemService(LauncherApps.class);
        LauncherApps.PinItemRequest req = null;
        try {
            req = la != null ? la.getPinItemRequest(getIntent()) : null;
        } catch (RuntimeException ignored) {
        }
        if (req != null && req.isValid() && req.getRequestType() == LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT) {
            ShortcutInfo si = req.getShortcutInfo();
            boolean ok;
            try {
                ok = req.accept();
            } catch (RuntimeException e) {
                ok = false;
            }
            CharSequence name = si != null ? si.getShortLabel() : null;
            String n = name != null ? "«" + name + "»" : "Ярлык";
            boolean game = si != null && Games.isEmulator(si.getPackage());
            String msg = !ok ? "Не удалось добавить ярлык"
                    : game ? n + " — в «Играх Vanks»" : n + " — в «Играх Vanks», раздел «Ярлыки»";
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
            if (ok && game) {
                // показать библиотеку с новой игрой
                try {
                    startActivity(new Intent(this, GamesActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (RuntimeException ignored) {
                }
            }
        }
        finish();
        overridePendingTransition(0, 0);
    }
}
