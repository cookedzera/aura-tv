package app.aura.home;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * The TV's own notifications button (and Fire TV's Inputs button) asks for a panel by an action of
 * its own. Sent to the home screen directly, Android 10 and later counted that as an ordinary app
 * and built a second copy of home each time; this invisible screen hands the action on to the one
 * home screen there is, where Control Center opens, and is gone before it's ever drawn.
 */
public class PanelBridge extends Activity {
    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        String act = getIntent() != null ? getIntent().getAction() : null;
        if (act != null) {
            try {
                startActivity(HomeActivity.intentFor(this).putExtra("aura_panel", act)
                        .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION));
            } catch (RuntimeException ignored) {
                // no home to hand it to: the button simply does nothing
            }
        }
        finish();
        overridePendingTransition(0, 0);
    }
}
