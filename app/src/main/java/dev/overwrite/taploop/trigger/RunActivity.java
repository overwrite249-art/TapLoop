package dev.overwrite.taploop.trigger;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ShortcutManager;
import android.os.Bundle;
import android.widget.Toast;

import dev.overwrite.taploop.model.Macro;
import dev.overwrite.taploop.model.MacroStore;
import dev.overwrite.taploop.service.TapService;
import dev.overwrite.taploop.ui.MainActivity;

/** invisible target for home screen shortcuts, starts the macro and goes away */
public class RunActivity extends Activity {
    static final String ACTION = "dev.overwrite.taploop.RUN_MACRO";
    static final String EXTRA_ID = "id";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Macro m = MacroStore.load(this, getIntent().getStringExtra(EXTRA_ID));
        TapService svc = TapService.get();
        if (m == null) {
            toast("That macro was deleted");
        } else if (svc == null) {
            toast("Turn on TapLoop in accessibility settings first");
            startActivity(new Intent(this, MainActivity.class));
        } else {
            String err = svc.playMacro(m);
            if (err != null) toast(err);
            ShortcutManager sm = getSystemService(ShortcutManager.class);
            if (sm != null) sm.reportShortcutUsed("run_" + m.id);
        }
        finish();
    }

    private void toast(String s) {
        Toast.makeText(getApplicationContext(), s, Toast.LENGTH_SHORT).show();
    }
}
