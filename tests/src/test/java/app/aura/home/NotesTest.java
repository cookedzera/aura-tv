package app.aura.home;

import android.app.Notification;
import android.content.Context;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowNotificationListenerService;

import java.util.List;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Control Center's notifications: what an app said is listed; what's ongoing, and Aura's own, isn't. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = AuraApp.class)
public class NotesTest {
    @Test
    public void listsWhatAppsSaid() {
        Context c = RuntimeEnvironment.getApplication();
        MediaWatch w = Robolectric.buildService(MediaWatch.class).create().get();
        w.onListenerConnected();
        ShadowNotificationListenerService s = shadowOf(w);
        Notification said = new Notification.Builder(c, "x").setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Download finished").setContentText("Season 2 is ready to watch").build();
        Notification ongoing = new Notification.Builder(c, "x").setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Syncing").setOngoing(true).build();
        s.addActiveNotification("com.netflix.ninja", 1, said);
        s.addActiveNotification("com.netflix.ninja", 2, ongoing);
        s.addActiveNotification(c.getPackageName(), 3, said);
        List<MediaWatch.Note> notes = MediaWatch.notes(c, 3);
        assertEquals(1, notes.size());
        assertEquals("Download finished", notes.get(0).title);
        assertEquals("Season 2 is ready to watch", notes.get(0).text);
        assertEquals("com.netflix.ninja", notes.get(0).pkg);
        // no VPN on a simulated TV, and no VPN app either
        assertFalse(Vpn.on(c));
        assertTrue(Vpn.apps(c).isEmpty());
        w.onListenerDisconnected();
        assertTrue(MediaWatch.notes(c, 3).isEmpty());
    }
}
