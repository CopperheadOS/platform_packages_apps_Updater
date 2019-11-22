package co.copperhead.updater;

import android.app.job.JobInfo;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.UserManager;
import android.widget.Toast;
import android.view.MenuItem;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentActivity;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;

public class Settings extends FragmentActivity {
    private static final int DEFAULT_NETWORK_TYPE = JobInfo.NETWORK_TYPE_UNMETERED;
    private static final String KEY_NETWORK_TYPE = "network_type";
    static final String KEY_BATTERY_NOT_LOW = "battery_not_low";
    static final String KEY_IDLE_REBOOT = "idle_reboot";
    static final String KEY_WAITING_FOR_REBOOT = "waiting_for_reboot";
    private static final String KEY_CHECK_FOR_UPDATES = "check_for_updates";
    private static final String KEY_INTERVAL = "check_for_updates_interval";

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
        setContentView(R.layout.activity_settings);
        getActionBar().setDisplayHomeAsUpEnabled(true);
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.settings_container, new SettingsFragment())
                .commit();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    public static class SettingsFragment extends PreferenceFragmentCompat implements
            Preference.OnPreferenceChangeListener {

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            getPreferenceManager().setStorageDeviceProtected();
            addPreferencesFromResource(R.xml.settings);

            final Preference checkForUpdates = findPreference(KEY_CHECK_FOR_UPDATES);
            checkForUpdates.setOnPreferenceClickListener((final Preference preference) -> {
                if (!PeriodicJob.scheduleCheckForUpdates(getContext(), true)) {
                    Toast.makeText(getActivity(), R.string.updater_already_running, Toast.LENGTH_SHORT).show();
                }
                return true;
            });

            final Preference networkType = findPreference(KEY_NETWORK_TYPE);
            networkType.setOnPreferenceChangeListener(this);
            final Preference interval = findPreference(KEY_INTERVAL);
            interval.setOnPreferenceChangeListener(this);
            final Preference batteryNotLow = findPreference(KEY_BATTERY_NOT_LOW);
            batteryNotLow.setOnPreferenceChangeListener(this);
            final Preference idleReboot = findPreference(KEY_IDLE_REBOOT);
            idleReboot.setOnPreferenceChangeListener(this);
        }

        @Override
        public boolean onPreferenceChange(Preference preference, Object newValue) {
            switch (preference.getKey()) {
                case KEY_NETWORK_TYPE:
                    final int value = Integer.parseInt((String) newValue);
                    getPreferences(getContext()).edit().putInt(KEY_NETWORK_TYPE, value).apply();
                    if (!getPreferences(getContext()).getBoolean(KEY_WAITING_FOR_REBOOT, false)) {
                        PeriodicJob.schedule(getContext());
                    }
                    return true;
                case KEY_INTERVAL:
                    if (Integer.parseInt((String) newValue) == 0) {
                        PeriodicJob.cancel(getContext());
                    } else {
                        PeriodicJob.schedule(getContext());
                    }
                    return true;
                case KEY_BATTERY_NOT_LOW:
                    getPreferences(getContext()).edit().putBoolean(KEY_BATTERY_NOT_LOW, (boolean) newValue).apply();
                    if (!getPreferences(getContext()).getBoolean(KEY_WAITING_FOR_REBOOT, false)) {
                        PeriodicJob.schedule(getContext());
                    }
                    return true;
                case KEY_IDLE_REBOOT:
                    if (!((Boolean) newValue)) {
                        IdleReboot.cancel(getContext());
                    }
                    return true;
            }
            return false;
        }

        @Override
        public void onResume() {
            super.onResume();
            final ListPreference networkType = (ListPreference) findPreference(KEY_NETWORK_TYPE);
            networkType.setValue(Integer.toString(getNetworkType(getContext())));
        }
    }
}
