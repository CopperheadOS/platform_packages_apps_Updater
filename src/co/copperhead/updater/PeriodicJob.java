package co.copperhead.updater;

import static android.os.Build.DEVICE;
import static android.os.Build.FINGERPRINT;
import static android.os.Build.VERSION.INCREMENTAL;

import static co.copperhead.updater.Settings.PREFERENCE_CHANNEL;
import static co.copperhead.updater.UpdateInstaller.IS_AB_UPDATE;

import java.io.File;
import java.text.NumberFormat;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.os.RecoverySystem;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.support.v4.app.NotificationCompat;
import android.text.format.Formatter;
import android.util.Log;
import android.widget.Toast;

import co.copperhead.updater.download.DownloadClient;
import co.copperhead.updater.download.DownloadClient.Headers;
import co.copperhead.updater.misc.StringGenerator;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.HttpURLConnection;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class PeriodicJob extends JobService {
    private static final String TAG = "PeriodicJob";

    private static final int JOB_ID_PERIODIC = 1;
    private static final int JOB_ID_RETRY = 2;
    private static final int JOB_ID_DOWNLOAD_UPDATE = 3;
    private static final int JOB_ID_CHECK_FOR_UPDATES = 4;
    private static final int JOB_ID_INSTALL_UPDATE = 5;

    private static final long INTERVAL_MILLIS = 60 * 60 * 1000;
    private static final long MIN_LATENCY_MILLIS = 4 * 60 * 1000;
    private static final int MAX_REPORT_INTERVAL_MS = 1000;

    private static final int CONNECT_TIMEOUT = 60000;
    private static final int READ_TIMEOUT = 60000;

    private static final String ONGOING_NOTIFICATION_CHANNEL = "ongoing_notification_channel";
    private static final String NOTIFICATION_CHANNEL = "updates2";

    private static final int NOTIFICATION_ID = 10;

    private static final int PENDING_REBOOT_ID = 1;

    static final File UPDATE_PATH = new File("/data/ota_package/update.zip");

    private static final String PAUSE_INTENT_ACTION = "co.copperhead.updater.action.PAUSE_DOWNLOAD";

    private NotificationManager mNotificationManager = null;
    private NotificationCompat.Builder mNotificationBuilder = null;
    private NotificationCompat.BigTextStyle mNotificationStyle = null;

    private DownloadClient mDownloadClient;

    static boolean isAbUpdate() {
        return SystemProperties.getBoolean("ro.build.ab_update", false);
    }

    static void scheduleDownload(final Context context, String updatePath, boolean resume) {
        final int networkType = Settings.getNetworkType(context);
        final boolean batteryNotLow = Settings.getBatteryNotLow(context);
        final JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler.getPendingJob(JOB_ID_DOWNLOAD_UPDATE) != null) {
            Log.d(TAG, "updater already running, ignoring request");
        } else {
            PersistableBundle extras = new PersistableBundle();
            extras.putString("update_path", updatePath);
            extras.putBoolean("resume", resume);
            final ComponentName serviceName = new ComponentName(context, PeriodicJob.class);
            final int result = scheduler.schedule(
                new JobInfo.Builder(JOB_ID_DOWNLOAD_UPDATE, serviceName)
                    .setRequiredNetworkType(networkType)
                    .setRequiresBatteryNotLow(batteryNotLow)
                    .setExtras(extras).build());
            if (result == JobScheduler.RESULT_FAILURE) {
                Log.d(TAG, "failed to download update");
            }
        }
    }

    static void scheduleInstall(final Context context) {
        final JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler.getPendingJob(JOB_ID_INSTALL_UPDATE) != null) {
            Log.d(TAG, "updater already running, ignoring request");
        } else {
            final ComponentName service = new ComponentName(context, PeriodicJob.class);
            final int result = scheduler.schedule(
                    new JobInfo.Builder(JOB_ID_INSTALL_UPDATE, service)
                        .setRequiresBatteryNotLow(Settings.getBatteryNotLow(context))
                        .build());
            if (result == JobScheduler.RESULT_FAILURE) {
                Log.d(TAG, "failed to install update");
            }
        }
    }
    
    static boolean scheduleCheckForUpdates(final Context context, final boolean showToast) {
        final int networkType = Settings.getNetworkType(context);
        final boolean batteryNotLow = Settings.getBatteryNotLow(context);
        final JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (isRunning(context)) {
            Log.d(TAG, "updater already running, ignoring request");
            return false;
        } else {
            final ComponentName service = new ComponentName(context, PeriodicJob.class);
            PersistableBundle extras = new PersistableBundle();
            extras.putBoolean("show_toast", showToast);
            final int result = scheduler.schedule(
                    new JobInfo.Builder(JOB_ID_CHECK_FOR_UPDATES, service)
                            .setRequiredNetworkType(networkType)
                            .setRequiresBatteryNotLow(batteryNotLow)
                            .setExtras(extras)
                            .build());
            if (result == JobScheduler.RESULT_FAILURE) {
                Log.e(TAG, "Failed to check for updates");
            }
        }
        return true;
    }

    static void schedule(final Context context) {
        final int interval = Settings.getInterval(context);
        final boolean enabled = Settings.autoCheckEnabled(context);
        final int networkType = Settings.getNetworkType(context);
        final boolean batteryNotLow = Settings.getBatteryNotLow(context);
        final JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        final JobInfo jobInfo = scheduler.getPendingJob(JOB_ID_PERIODIC);
        if (jobInfo != null &&
                jobInfo.getNetworkType() == networkType &&
                jobInfo.isRequireBatteryNotLow() == batteryNotLow &&
                jobInfo.isPersisted() &&
                jobInfo.getIntervalMillis() == (interval * INTERVAL_MILLIS)) {
            Log.d(TAG, "Periodic job already registered");
            return;
        }
        if (!enabled) {
            scheduler.cancel(JOB_ID_PERIODIC);
            return;
        }

        final ComponentName serviceName = new ComponentName(context, PeriodicJob.class);
        final int result = scheduler.schedule(new JobInfo.Builder(JOB_ID_PERIODIC, serviceName)
                .setRequiredNetworkType(networkType)
                .setRequiresBatteryNotLow(batteryNotLow)
                .setPersisted(true)
                .setPeriodic(interval * INTERVAL_MILLIS)
                .build());
        if (result == JobScheduler.RESULT_FAILURE) {
            Log.d(TAG, "Periodic job schedule failed");
        }
    }

    static void scheduleRetry(final Context context) {
        final JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        final ComponentName serviceName = new ComponentName(context, PeriodicJob.class);
        final int result = scheduler.schedule(new JobInfo.Builder(JOB_ID_RETRY, serviceName)
                .setRequiredNetworkType(Settings.getNetworkType(context))
                .setRequiresBatteryNotLow(Settings.getBatteryNotLow(context))
                .setMinimumLatency(MIN_LATENCY_MILLIS)
                .build());
        if (result == JobScheduler.RESULT_FAILURE) {
            Log.d(TAG, "Retry job schedule failed");
        }
    }

    private static boolean isRunning(final Context context) {
        final JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        return (scheduler.getPendingJob(JOB_ID_CHECK_FOR_UPDATES) != null
                || scheduler.getPendingJob(JOB_ID_DOWNLOAD_UPDATE) != null
                || scheduler.getPendingJob(JOB_ID_INSTALL_UPDATE) != null);        
    }

    static void cancel(final Context context) {
        final JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        scheduler.cancel(JOB_ID_RETRY);
        scheduler.cancel(JOB_ID_DOWNLOAD_UPDATE);
        scheduler.cancel(JOB_ID_CHECK_FOR_UPDATES);
    }

    private static PendingIntent getCancelPendingIntent(Context context) {
        final Intent intent = new Intent(context, UpdateReceiver.class);
        intent.setAction("co.copperhead.updater.CANCEL");
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private final String getUrl(String path) {
        return getString(IS_AB_UPDATE ? R.string.url : R.string.url_legacy) + path;
    }

    private static ZipEntry getEntry(final ZipFile zipFile, final String name)
            throws GeneralSecurityException {
        final ZipEntry entry = zipFile.getEntry(name);
        if (entry == null) {
            throw new GeneralSecurityException("missing zip entry: " + name);
        }
        return entry;
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        mNotificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        createNotificationBuilder();
        Log.d(TAG, "onStartJob id: " + params.getJobId());
        if (params.getJobId() == JOB_ID_DOWNLOAD_UPDATE) {
            IntentFilter filter = new IntentFilter(PAUSE_INTENT_ACTION);
            registerReceiver(mReceiver, filter);
            downloadUpdate(params, params.getExtras().getBoolean("resume", false));
        } else if (params.getJobId() == JOB_ID_CHECK_FOR_UPDATES) {
            new Thread(new Runnable() {
                public void run() {
                    checkForUpdates(params);
                }
            }).start();
        } else if (params.getJobId() == JOB_ID_INSTALL_UPDATE) {
            new Thread(new Runnable() {
                public void run() {
                    installUpdate(params);
                }
            }).start();
        } else {
            scheduleCheckForUpdates(this, false);
        }
        return params.getJobId() != JOB_ID_PERIODIC;
    }

    @Override
    public boolean onStopJob(final JobParameters params) {
        unregisterReceiver(mReceiver);
        return false;
    }

    private void installUpdate(JobParameters params) {
        UpdateInstaller installer = new UpdateInstaller(getApplicationContext());
        mNotificationStyle.bigText(getString(R.string.prepare_zip_message));
        mNotificationManager.notify(NOTIFICATION_ID, mNotificationBuilder.build());
        installer.setCallback(new UpdateInstaller.UpdateCallback() {
            @Override
            public void onProgress(int progress, boolean finalizing) {
                mNotificationBuilder.setProgress(100, progress, false);
                mNotificationBuilder.mActions.clear();
                String percent = NumberFormat.getPercentInstance().format(progress / 100.f);
                mNotificationStyle.setSummaryText(percent);
                mNotificationStyle.bigText(IS_AB_UPDATE ? (finalizing ?
                        getString(R.string.finalizing_package)
                            : getString(R.string.prepare_zip_message))
                                : getString(R.string.preparing_ota_first_boot));
                mNotificationManager.notify(NOTIFICATION_ID, mNotificationBuilder.build());
            }

            @Override
            public void onComplete() {
                if (Settings.getPreferences(PeriodicJob.this).getBoolean(
                        Settings.KEY_IDLE_REBOOT, false)) {
                    IdleReboot.schedule(PeriodicJob.this);
                }
                final PendingIntent reboot = PendingIntent.getBroadcast(
                        getApplicationContext(), PENDING_REBOOT_ID, new Intent(
                            getApplicationContext(), RebootReceiver.class), 0);

                mNotificationBuilder.setStyle(null);
                mNotificationBuilder.setProgress(0, 0, false);
                String text = getString(R.string.installing_update_finished);
                mNotificationBuilder.setContentText(text);
                mNotificationBuilder.addAction(R.drawable.ic_system_update_white_24dp,
                        getString(R.string.reboot), reboot);
                mNotificationManager.notify(NOTIFICATION_ID, mNotificationBuilder.build());
                jobFinished(params, false);
            }
                    
            @Override
            public void onError(int errorCode) {
                mNotificationBuilder.setStyle(null);
                mNotificationBuilder.setProgress(0, 0, false);
                String text = getString(R.string.update_failed_notification_msg);
                mNotificationBuilder.setContentText(text);
                mNotificationBuilder.setOngoing(false);
                mNotificationManager.notify(NOTIFICATION_ID, mNotificationBuilder.build());
                jobFinished(params, false);
            }
        });
        installer.applyUpdate();
    }

    private HttpURLConnection fetchData(final String path) throws IOException {
        final URL url = new URL(
                getString(isAbUpdate() ? R.string.url : R.string.url_legacy) + path);
        Log.d(TAG, "URL=" + url.toString());
        final HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
        urlConnection.setConnectTimeout(CONNECT_TIMEOUT);
        urlConnection.setReadTimeout(READ_TIMEOUT);
        urlConnection.setUseCaches(false);
        return urlConnection;
    }

    private boolean otaExists(final String path) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) fetchData(path);
            conn.setRequestMethod("HEAD");
            return (conn.getResponseCode() == HttpURLConnection.HTTP_OK);
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private void checkForUpdates(JobParameters params) {
        final SharedPreferences preferences = Settings.getPreferences(this);
        final String channel = SystemProperties.get("sys.update.channel",
                preferences.getString(PREFERENCE_CHANNEL, "stable"));
        HttpURLConnection conn = null;
        try {
            conn = fetchData(DEVICE + "-" + channel);
            InputStream input = conn.getInputStream();
            Log.d(TAG, "response - " + conn.getResponseCode());
            final BufferedReader reader = new BufferedReader(new InputStreamReader(input));
            final String meta = reader.readLine();
            final String[] metadata = meta.split(" ");
            reader.close();

            final String targetIncremental = metadata[0];
            final long targetBuildDate = Long.parseLong(metadata[1]);
            final long sourceBuildDate = SystemProperties.getLong("ro.build.date.utc", 0);
            if (targetBuildDate <= sourceBuildDate) {
                Log.d(TAG, "targetBuildDate: "
                        + targetBuildDate + " not higher than sourceBuildDate: " + sourceBuildDate);
                if (params.getExtras().getBoolean("show_toast", false)) {
                    new Handler(Looper.getMainLooper()).post(() -> {
                        Toast.makeText(getApplicationContext(), R.string.no_updates_found,
                                Toast.LENGTH_SHORT).show();
                    });
                }
                jobFinished(params, false);
                return;
            }
            preferences.edit().putLong("target_build_date", targetBuildDate).apply();
            long downloaded = UPDATE_PATH.length();

            final String incrementalUpdate =
                    DEVICE + "-incremental-" + INCREMENTAL + "-" + targetIncremental + ".zip";
            final String fullUpdate = DEVICE + "-ota_update-" + targetIncremental + ".zip";

            Log.d(TAG, "incr found - " + otaExists(incrementalUpdate));
            String updatePath = fullUpdate;
            if (otaExists(incrementalUpdate)) {
                updatePath = incrementalUpdate;
            }

            Intent downloadIntent = new Intent(this, UpdateReceiver.class);
            downloadIntent.setAction(UpdateReceiver.DOWNLOAD_UPDATE_ACTION);
            preferences.edit().putString("update_path", updatePath).apply();
            downloadIntent.putExtra("update_path", updatePath);

            final NotificationChannel notifChannel = new NotificationChannel(NOTIFICATION_CHANNEL,
                    getString(R.string.notification_channel), NotificationManager.IMPORTANCE_HIGH);

            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

            nm.createNotificationChannel(notifChannel);

            NotificationCompat.Builder nc =
                new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL)
                    .setSmallIcon(R.drawable.ic_system_update_white_24dp)
                    .setContentTitle(getString(R.string.update_found_notification_title))
                    .setContentText(getString(R.string.update_found_notification_msg))
                    .setContentIntent(PendingIntent.getBroadcast(this, 101, downloadIntent, 0))
                    .setAutoCancel(true);
            nm.notify(NOTIFICATION_ID, nc.build());
        } catch (IOException e) {
            Log.e(TAG, "failed to check for updates", e);
            scheduleRetry(this);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
            jobFinished(params, false);
        }
    }

    private boolean verifyPackage(File update) {
        try {
            RecoverySystem.verifyPackage(update, null, null);
            UPDATE_PATH.setReadable(true, false);
            final ZipFile zipFile = new ZipFile(UPDATE_PATH);

            final String channel = SystemProperties.get("sys.update.channel",
                    Settings.getPreferences(this).getString(PREFERENCE_CHANNEL, "stable"));

            final long targetBuildDate =
                    Settings.getPreferences(this).getLong("target_build_date", 0);

            final ZipEntry metadata = getEntry(zipFile, "META-INF/com/android/metadata");
            final BufferedReader reader = new BufferedReader(
                    new InputStreamReader(zipFile.getInputStream(metadata)));
            String device = null;
            String serialno = null;
            String type = null;
            String sourceIncremental = null;
            String sourceFingerprint = null;
            String streamingPropertyFiles[] = null;
            long timestamp = 0;
            for (String line; (line = reader.readLine()) != null;) {
                final String[] pair = line.split("=");
                if ("post-timestamp".equals(pair[0])) {
                    timestamp = Long.parseLong(pair[1]);
                } else if ("serialno".equals(pair[0])) {
                    serialno = pair[1];
                } else if ("pre-device".equals(pair[0])) {
                    device = pair[1];
                } else if ("ota-type".equals(pair[0])) {
                    type = pair[1];
                } else if ("ota-streaming-property-files".equals(pair[0])) {
                    streamingPropertyFiles = pair[1].trim().split(",");
                } else if ("pre-build-incremental".equals(pair[0])) {
                    sourceIncremental = pair[1];
                } else if ("pre-build".equals(pair[0])) {
                    sourceFingerprint = pair[1];
                }
            }
            if (timestamp != targetBuildDate) {
                throw new GeneralSecurityException("timestamp does not match server metadata");
            }
            if (!DEVICE.equals(device)) {
                throw new GeneralSecurityException("device mismatch");
            }
            if (serialno != null) {
                if ("stable".equals(channel) || "beta".equals(channel)) {
                    throw new GeneralSecurityException(
                            "serialno constraint not permitted for channel " + channel);
                }
                if (!serialno.equals(Build.getSerial())) {
                    throw new GeneralSecurityException("serialno mismatch");
                }
            }
            if ("AB".equals(type) != IS_AB_UPDATE) {
                throw new GeneralSecurityException("update type does not match device");
            }
            if (sourceIncremental != null && !sourceIncremental.equals(INCREMENTAL)) {
                throw new GeneralSecurityException("source incremental mismatch");
            }
            if (sourceFingerprint != null && !sourceFingerprint.equals(FINGERPRINT)) {
                throw new GeneralSecurityException("source fingerprint mismatch");
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error while verify the file", e);
        }
        return false;
    }

    private BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (PAUSE_INTENT_ACTION.equals(intent.getAction())) {
                mDownloadClient.cancel();
                mNotificationBuilder.mActions.clear();
                String text = getString(R.string.download_paused_notification);
                mNotificationStyle.bigText(text);
                mNotificationBuilder.addAction(android.R.drawable.ic_media_play,
                        getString(R.string.resume_button),
                        getResumePendingIntent());
                mNotificationBuilder.setTicker(text);
                mNotificationBuilder.setOngoing(false);
                mNotificationBuilder.setAutoCancel(false);
                mNotificationManager.notify(NOTIFICATION_ID, mNotificationBuilder.build());
                cancel(context);
            }
        }
    };

    private void downloadUpdate(JobParameters params, boolean resume) {

        DownloadClient.DownloadCallback downloadCallback =
                new DownloadClient.DownloadCallback(){
    
            @Override
            public void onSuccess(File destination) {
                Log.d(TAG, "Download Complete!");
                mNotificationBuilder.mActions.clear();
                mNotificationBuilder.setProgress(100, 0, true);
                mNotificationStyle.setSummaryText(null);
                mNotificationBuilder.setStyle(mNotificationStyle);
                String text = getString(R.string.verifying_download_notification);
                mNotificationStyle.bigText(text);
                mNotificationBuilder.setTicker(text);
                mNotificationManager.notify(NOTIFICATION_ID, mNotificationBuilder.build());
                if (verifyPackage(destination)) {
                    Log.d(TAG, "Download Verified");
                    if (IS_AB_UPDATE) {
                        Intent intent = new Intent(PeriodicJob.this, UpdateReceiver.class);
                        intent.setAction(UpdateReceiver.INSTALL_UPDATE_ACTION);
                        sendBroadcast(intent);
                    } else {
                        mNotificationBuilder.setStyle(null);
                        mNotificationBuilder.setProgress(0, 0, false);
                        text = getString(R.string.notification_text_legacy);
                        mNotificationBuilder.setContentText(text);
                        mNotificationBuilder.setTicker(text);
                        mNotificationBuilder.setOngoing(false);
                        mNotificationBuilder.setAutoCancel(true);
                        mNotificationBuilder.addAction(R.drawable.ic_restart,
                                getString(R.string.reboot), getInstallUpdateLegacyPendingIntent());
                        mNotificationManager.notify(NOTIFICATION_ID, mNotificationBuilder.build());
                    }
                } else {
                    mNotificationBuilder.mActions.clear();
                    mNotificationBuilder.setStyle(null);
                    mNotificationBuilder.setProgress(0, 0, false);
                    text = getString(R.string.verification_failed_notification);
                    mNotificationBuilder.setContentText(text);
                    mNotificationBuilder.setTicker(text);
                    mNotificationBuilder.setOngoing(false);
                    mNotificationBuilder.setAutoCancel(true);
                    mNotificationManager.notify(NOTIFICATION_ID, mNotificationBuilder.build());
                }
                jobFinished(params, false);
            }
    
            @Override
            public void onResponse(int statusCode, String url, Headers headers) {
            }

            @Override
            public void onFailure(boolean cancelled) {
                if (!cancelled) {
                    mNotificationBuilder.mActions.clear();
                    String text = getString(R.string.download_paused_error_notification);
                    mNotificationStyle.bigText(text);
                    mNotificationBuilder.addAction(android.R.drawable.ic_media_play,
                            getString(R.string.resume_button),
                            getResumePendingIntent());
                    mNotificationBuilder.setTicker(text);
                    mNotificationBuilder.setOngoing(false);
                    mNotificationBuilder.setAutoCancel(false);
                    mNotificationManager.notify(NOTIFICATION_ID, mNotificationBuilder.build());
                }
                jobFinished(params, false);
            }
        };

        DownloadClient.ProgressListener progressListener =
                new DownloadClient.ProgressListener() {
            private long mLastUpdate = 0;
            private int mProgress = 0;
            @Override
            public void update(long bytesRead, long contentLength,
                    long speed, long eta, boolean done) {
                if (contentLength <= 0) {
                    return;
                }
                final long now = SystemClock.elapsedRealtime();
                int progress = Math.round(bytesRead * 100 / contentLength);
                if (progress != mProgress || mLastUpdate - now > MAX_REPORT_INTERVAL_MS) {
                    mProgress = progress;
                    mLastUpdate = now;

                    mNotificationBuilder.setProgress(100, progress, false);

                    String percent = NumberFormat.getPercentInstance().format(progress / 100.f);
                    mNotificationStyle.setSummaryText(percent);

                    String speedString = Formatter.formatFileSize(getApplicationContext(), speed);
                    CharSequence etaString =
                            StringGenerator.formatETA(getApplicationContext(), eta * 1000);
                    mNotificationStyle.bigText(
                            getString(R.string.text_download_speed, etaString, speedString));

                    mNotificationManager.notify(NOTIFICATION_ID, mNotificationBuilder.build());
                }
            }
        };

        String updateFile = Settings.getPreferences(this).getString("update_path", "unknown");
        Log.d(TAG, "updateFile - " + updateFile);

        try {
            mDownloadClient = new DownloadClient.Builder()
                    .setUrl(getUrl(updateFile))
                    .setDestination(UPDATE_PATH)
                    .setDownloadCallback(downloadCallback)
                    .setProgressListener(progressListener)
                    .setUseDuplicateLinks(true)
                    .build();
        } catch (IOException e) {
            e.printStackTrace();
            jobFinished(params, false);
            return;
        }
        mNotificationManager.notify(NOTIFICATION_ID, mNotificationBuilder.build());
        if (resume) {
            mDownloadClient.resume();
        } else {
            mDownloadClient.start();
        }
    }

    private void createNotificationBuilder() {
        NotificationChannel channel = new NotificationChannel(ONGOING_NOTIFICATION_CHANNEL,
                getString(R.string.ongoing_channel_title), NotificationManager.IMPORTANCE_LOW);

        String text = getString(R.string.downloading_notification);
        mNotificationManager.createNotificationChannel(channel);
        if (mNotificationBuilder == null) {
            mNotificationBuilder = new NotificationCompat.Builder(
                    this, ONGOING_NOTIFICATION_CHANNEL);
            mNotificationStyle = new NotificationCompat.BigTextStyle();
        }
        mNotificationBuilder.setSmallIcon(R.drawable.ic_system_update_white_24dp);
        mNotificationBuilder.setShowWhen(false);
        mNotificationBuilder.addAction(android.R.drawable.ic_media_pause,
                getString(R.string.pause_button),
                getPausePendingIntent());
        mNotificationStyle.bigText(text);
        mNotificationBuilder.setStyle(mNotificationStyle);
        mNotificationBuilder.setDeleteIntent(getCancelPendingIntent(this));
        mNotificationBuilder.setTicker(text);
        mNotificationBuilder.setOngoing(true);
        mNotificationBuilder.setAutoCancel(false);
    }
    
    private PendingIntent getPausePendingIntent() {
        return PendingIntent.getBroadcast(this, 0, new Intent(PAUSE_INTENT_ACTION), 0);
    }

    private PendingIntent getResumePendingIntent() {
        Intent resume = new Intent(this, UpdateReceiver.class);
        resume.setAction(UpdateReceiver.RESUME_DOWNLOAD_ACTION);
        return PendingIntent.getBroadcast(this, 0, resume, 0);
    }

    private PendingIntent getInstallUpdateLegacyPendingIntent() {
        Intent install = new Intent(this, UpdateReceiver.class);
        install.setAction(UpdateReceiver.INSTALL_UPDATE_LEGACY_ACTION);
        return PendingIntent.getBroadcast(this, 0, install, 0);
    }
}
