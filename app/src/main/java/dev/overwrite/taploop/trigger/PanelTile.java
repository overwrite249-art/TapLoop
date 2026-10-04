package dev.overwrite.taploop.trigger;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import dev.overwrite.taploop.service.TapService;
import dev.overwrite.taploop.ui.MainActivity;

/** quick settings tile that shows / hides the floating panel */
public class PanelTile extends TileService {

    @Override
    public void onStartListening() {
        refresh();
    }

    @Override
    public void onClick() {
        if (isLocked()) unlockAndRun(this::toggle);
        else toggle();
    }

    private void toggle() {
        TapService svc = TapService.get();
        if (svc == null) {
            openApp();
            return;
        }
        if (svc.isPanelShown()) {
            svc.stopAll();
            svc.hidePanel();
        } else {
            svc.showPanel();
        }
        refresh();
    }

    // the Intent overload is only reached below 14, where it still works
    @SuppressLint("StartActivityAndCollapseDeprecated")
    @SuppressWarnings("deprecation")
    private void openApp() {
        Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // 14+ only takes a PendingIntent here
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        } else {
            startActivityAndCollapse(i);
        }
    }

    private void refresh() {
        Tile t = getQsTile();
        if (t == null) return;
        TapService svc = TapService.get();
        boolean on = svc != null && svc.isPanelShown();
        t.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            t.setSubtitle(svc == null ? "Accessibility off" : (on ? "Shown" : "Hidden"));
        }
        t.updateTile();
    }
}
