package com.jakewharton.telecine;

import android.app.AlertDialog;
import android.app.Notification;
import android.app.Service;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.IBinder;
import android.provider.Settings;
import android.support.annotation.NonNull;
import android.view.SurfaceHolder;

import net.majorkernelpanic.streaming.Session;
import net.majorkernelpanic.streaming.SessionBuilder;
import net.majorkernelpanic.streaming.SessionBuilderNew;
import net.majorkernelpanic.streaming.SessionNew;
import net.majorkernelpanic.streaming.audio.AudioQuality;
import net.majorkernelpanic.streaming.gl.SurfaceView;
import net.majorkernelpanic.streaming.rtsp.RtspClientNew;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.inject.Inject;
import javax.inject.Provider;

import timber.log.Timber;

import static android.app.Notification.PRIORITY_MIN;

public final class TelecineService extends Service{
    private static final String EXTRA_RESULT_CODE = "result-code";
    private static final String EXTRA_DATA        = "data";
    private static final int    NOTIFICATION_ID   = 99118822;
    private static final String SHOW_TOUCHES      = "show_touches";

    public static Intent newIntent(Context context, int resultCode, Intent data) {
        Intent intent = new Intent(context, TelecineService.class);
        intent.putExtra(EXTRA_RESULT_CODE, resultCode);
        intent.putExtra(EXTRA_DATA, data);
        return intent;
    }

    @Inject
    @ShowCountdown
    Provider<Boolean> showCountdownProvider;
    @Inject
    @VideoSizePercentage
    Provider<Integer> videoSizePercentageProvider;
    @Inject
    @RecordingNotification
    Provider<Boolean> recordingNotificationProvider;
    @Inject
    @ShowTouches
    Provider<Boolean> showTouchesProvider;

    @Inject
    Analytics       analytics;
    @Inject
    ContentResolver contentResolver;

    private boolean          running;
    private RecordingSession recordingSession;

    private final RecordingSession.Listener listener = new RecordingSession.Listener() {
        @Override
        public void onStart() {
            if (showTouchesProvider.get()) {
                Settings.System.putInt(contentResolver, SHOW_TOUCHES, 1);
            }

            if (!recordingNotificationProvider.get()) {
                return; // No running notification was requested.
            }

            Context context = getApplicationContext();
            String title = context.getString(R.string.notification_recording_title);
            String subtitle = context.getString(R.string.notification_recording_subtitle);
            Notification notification = new Notification.Builder(context) //
                    .setContentTitle(title)
                    .setContentText(subtitle)
                    .setSmallIcon(R.drawable.ic_videocam_white_24dp)
                    .setColor(context.getResources().getColor(R.color.primary_normal))
                    .setAutoCancel(true)
                    .setPriority(PRIORITY_MIN)
                    .build();

            Timber.d("Moving service into the foreground with recording notification.");
            startForeground(NOTIFICATION_ID, notification);
        }

        @Override
        public void onStop() {
            if (showTouchesProvider.get()) {
                Settings.System.putInt(contentResolver, SHOW_TOUCHES, 0);
            }

            stopForeground(true /* remove notification */);
        }

        @Override
        public void onEnd() {
            Timber.d("Shutting down.");
            stopSelf();
        }
    };

    @Override
    public int onStartCommand(@NonNull Intent intent, int flags, int startId) {
        if (running) {
            Timber.d("Already running! Ignoring...");
            return START_NOT_STICKY;
        }
        Timber.d("Starting up!");
        running = true;

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent data = intent.getParcelableExtra(EXTRA_DATA);
        if (resultCode == 0 || data == null) {
            throw new IllegalStateException("Result code or data missing.");
        }

        ((TelecineApplication) getApplication()).inject(this);

        recordingSession =
                new RecordingSession(this, listener, resultCode, data, analytics, showCountdownProvider,
                        videoSizePercentageProvider);
        recordingSession.showOverlay();

        return START_NOT_STICKY;
    }



    @Override
    public void onDestroy() {
        recordingSession.destroy();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(@NonNull Intent intent) {
        throw new AssertionError("Not supported.");
    }

}
