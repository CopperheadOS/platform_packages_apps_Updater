package co.copperhead.updater;

import android.app.job.JobInfo;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.UserManager;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceManager;
import android.widget.Toast;

public class Settings extends PreferenceActivity {
    private static final int DEFAULT_NETWORK_TYPE = JobInfo.NETWORK_TYPE_UNMETERED;
    private static final String KEY_NETWORK_TYPE = "network_type";
    static final String KEY_BATTERY_NOT_LOW = "battery_not_low";
    static final String KEY_IDLE_REBOOT = "idle_reboot";
    static final String KEY_WAITING_FOR_REBOOT = "waiting_for_reboot";
    private static final String KEY_CHECK_FOR_UPDATES = "check_for_updates";
    private static final String KEY_INTERVAL = "check_for_updates_interval";
    private static final String KEY_MESSAGING_OPT_OUT = "messaging_opt_out";

    static final String PREFERENCE_CHANNEL = "channel";

    static SharedPreferences getPreferences(final Context context) {
        final Context deviceContext = context.createDeviceProtectedStorageContext();
        return PreferenceManager.getDefaultSharedPreferences(deviceContext);
    }

    static int getInterval(final Context context) {
        return Integer.parseInt(getPreferences(context).getString(KEY_INTERVAL, "24"));
    }

    static boolean autoCheckEnabled(final Context context) {
        return getInterval(context) != 0;
    }

    static int getNetworkType(final Context context) {
        return getPreferences(context).getInt(KEY_NETWORK_TYPE, DEFAULT_NETWORK_TYPE);
    }

    static boolean getBatteryNotLow(final Context context) {
        return getPreferences(context).getBoolean(KEY_BATTERY_NOT_LOW, true);
    }

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!UserManager.get(this).isSystemUser()) {
            throw new SecurityException("system user only");
        }
        getActionBar().setDisplayHomeAsUpEnabled(true);
        getPreferenceManager().setStorageDeviceProtected();
        addPreferencesFromResource(R.xml.settings);

        final Preference checkForUpdates = findPreference(KEY_CHECK_FOR_UPDATES);
        checkForUpdates.setOnPreferenceClickListener((final Preference preference) -> {
            if (!PeriodicJob.scheduleCheckForUpdates(this, true)) {
                Toast.makeText(this, R.string.updater_already_running, Toast.LENGTH_SHORT).show();
            }
            return true;
        });

        final Preference networkType = findPreference(KEY_NETWORK_TYPE);
        networkType.setOnPreferenceChangeListener((final Preference preference, final Object newValue) -> {
            final int value = Integer.parseInt((String) newValue);
            getPreferences(this).edit().putInt(KEY_NETWORK_TYPE, value).apply();
            if (!getPreferences(this).getBoolean(KEY_WAITING_FOR_REBOOT, false)) {
                PeriodicJob.schedule(this);
            }
            return true;
        });

        final Preference interval = findPreference(KEY_INTERVAL);
        interval.setOnPreferenceChangeListener((final Preference preference,
                final Object newValue) -> {
                    final int value = Integer.parseInt((String) newValue);
                    if (value == 0) {
                        PeriodicJob.cancel(this);
                    } else {
                        PeriodicJob.schedule(this);
                    }
                    return true;
                });

        final Preference batteryNotLow = findPreference(KEY_BATTERY_NOT_LOW);
        batteryNotLow.setOnPreferenceChangeListener((final Preference preference, final Object newValue) -> {
            getPreferences(this).edit().putBoolean(KEY_BATTERY_NOT_LOW, (boolean) newValue).apply();
            if (!getPreferences(this).getBoolean(KEY_WAITING_FOR_REBOOT, false)) {
                PeriodicJob.schedule(this);
            }
            return true;
        });

        final Preference idleReboot = findPreference(KEY_IDLE_REBOOT);
        idleReboot.setOnPreferenceChangeListener((final Preference preference, final Object newValue) -> {
            final boolean value = (Boolean) newValue;
            if (!value) {
                IdleReboot.cancel(this);
            }
            return true;
        });

        final Preference messagingOptOut = findPreference(KEY_MESSAGING_OPT_OUT);
        messagingOptOut.setOnPreferenceChangeListener((final Preference preference, final Object newValue) -> {
            final boolean value = (Boolean) newValue;
            android.provider.Settings.Global.putInt(getContentResolver(), KEY_MESSAGING_OPT_OUT, value ? 1 : 0);
            return true;
       });
    }

    @Override
    public void onResume() {
        super.onResume();
        final ListPreference networkType = (ListPreference) findPreference(KEY_NETWORK_TYPE);
        networkType.setValue(Integer.toString(getNetworkType(this)));
    }
}
