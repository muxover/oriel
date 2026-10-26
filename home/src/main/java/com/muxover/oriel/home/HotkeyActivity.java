package com.muxover.oriel.home;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/**
 * Where the TV's own remote buttons land once the maker's panels are switched off. Oriel's keeper (root)
 * reads each button from the system log and starts this with its key number; nothing is drawn here.
 */
public class HotkeyActivity extends Activity {
    /** Hikeen remotes: Source, a picture/sound button, Sleep, and holding Power (sent as -1 by the keeper). */
    static final int SOURCE = 2001, PICTURE = 2010, SLEEP = 223, LONG_POWER = -1;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        int key = getIntent().getIntExtra("key", 0);
        Log.i("Oriel", "remote button " + key);
        switch (key) {
            case SOURCE:
                InputActivity.open(this);
                break;
            case SLEEP:
                SleepActivity.open(this);
                break;
            case LONG_POWER:
                PowerActivity.open(this);
                break;
            case PICTURE:
                startActivity(new Intent(this, SettingsActivity.class).putExtra("section", SettingsActivity.PICTURE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                break;
            default:
                break;
        }
        finish();
        overridePendingTransition(0, 0);
    }
}
