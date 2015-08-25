package com.jakewharton.telecine;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.hardware.display.VirtualDisplay;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.SurfaceHolder;
import android.view.WindowManager;

import com.google.android.gms.analytics.HitBuilders;

import net.majorkernelpanic.streaming.SessionBuilder;
import net.majorkernelpanic.streaming.SessionBuilderNew;
import net.majorkernelpanic.streaming.SessionNew;
import net.majorkernelpanic.streaming.rtsp.RtspClientNew;
import net.majorkernelpanic.streaming.video.RecordingInfo;

import java.io.File;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.inject.Provider;

import timber.log.Timber;

import static android.content.Context.MEDIA_PROJECTION_SERVICE;
import static android.content.Context.NOTIFICATION_SERVICE;
import static android.content.Context.WINDOW_SERVICE;
import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.os.Environment.DIRECTORY_MOVIES;

final class RecordingSession implements RtspClientNew.Callback,
        SessionNew.Callback, SurfaceHolder.Callback {
    static final int NOTIFICATION_ID = 522592;

    private static final String DISPLAY_NAME = "telecine";
    private static final String MIME_TYPE    = "video/mp4";
    private SessionNew    session;
    private RtspClientNew mClient;

    @Override
    public void onBitrateUpdate(long bitrate) {

    }

    @Override
    public void onSessionError(int reason, int streamType, Exception e) {

    }

    @Override
    public void onPreviewStarted() {

    }

    @Override
    public void onSessionConfigured() {

    }

    @Override
    public void onSessionStarted() {

    }

    @Override
    public void onSessionStopped() {

    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {

    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {

    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {

    }

    @Override
    public void onRtspUpdate(int message, Exception exception) {

    }

    interface Listener {
        /**
         * Invoked immediately prior to the start of recording.
         */
        void onStart();

        /**
         * Invoked immediately after the end of recording.
         */
        void onStop();

        /**
         * Invoked after all work for this session has completed.
         */
        void onEnd();
    }

    private final Handler mainThread = new Handler(Looper.getMainLooper());

    private final Context  context;
    private final Listener listener;
    private final int      resultCode;
    private final Intent   data;

    private final Analytics         analytics;
    private final Provider<Boolean> showCountDown;
    private final Provider<Integer> videoSizePercentage;

    private final File outputRoot;
    private final DateFormat fileFormat =
            new SimpleDateFormat("'Telecine_'yyyy-MM-dd-HH-mm-ss'.mp4'", Locale.US);

    private final NotificationManager    notificationManager;
    private final WindowManager          windowManager;
    private final MediaProjectionManager projectionManager;

    private OverlayView     overlayView;
    private MediaRecorder   recorder;
    private MediaProjection projection;
    private VirtualDisplay  display;
    private String          outputFile;
    private boolean         running;
    private long            recordingStartNanos;


    RecordingSession(Context context, Listener listener, int resultCode, Intent data,
                     Analytics analytics, Provider<Boolean> showCountDown, Provider<Integer> videoSizePercentage) {
        this.context = context;
        this.listener = listener;
        this.resultCode = resultCode;
        this.data = data;
        this.analytics = analytics;

        this.showCountDown = showCountDown;
        this.videoSizePercentage = videoSizePercentage;

        File picturesDir = Environment.getExternalStoragePublicDirectory(DIRECTORY_MOVIES);
        outputRoot = new File(picturesDir, "Telecine");

        notificationManager = (NotificationManager) context.getSystemService(NOTIFICATION_SERVICE);
        windowManager = (WindowManager) context.getSystemService(WINDOW_SERVICE);
        projectionManager = (MediaProjectionManager) context.getSystemService(MEDIA_PROJECTION_SERVICE);
    }

    public void showOverlay() {
        Timber.d("Adding overlay view to window.");

        OverlayView.Listener overlayListener = new OverlayView.Listener() {
            @Override
            public void onCancel() {
                cancelOverlay();
            }

            @Override
            public void onStart() {
                startRecording();
            }

            @Override
            public void onStop() {
                stopRecording();
            }
        };
        overlayView = OverlayView.create(context, overlayListener, showCountDown.get());
        windowManager.addView(overlayView, OverlayView.createLayoutParams(context));

        analytics.send(new HitBuilders.EventBuilder() //
                .setCategory(Analytics.CATEGORY_RECORDING)
                .setAction(Analytics.ACTION_OVERLAY_SHOW)
                .build());
    }

    private void hideOverlay() {
        if (overlayView != null) {
            Timber.d("Removing overlay view from window.");
            windowManager.removeView(overlayView);
            overlayView = null;

            analytics.send(new HitBuilders.EventBuilder() //
                    .setCategory(Analytics.CATEGORY_RECORDING)
                    .setAction(Analytics.ACTION_OVERLAY_HIDE)
                    .build());
        }
    }

    private void cancelOverlay() {
        hideOverlay();
        listener.onEnd();

        analytics.send(new HitBuilders.EventBuilder() //
                .setCategory(Analytics.CATEGORY_RECORDING)
                .setAction(Analytics.ACTION_OVERLAY_CANCEL)
                .build());
    }

    private RecordingInfo getRecordingInfo() {
        DisplayMetrics displayMetrics = new DisplayMetrics();
        WindowManager wm = (WindowManager) context.getSystemService(WINDOW_SERVICE);
        wm.getDefaultDisplay().getRealMetrics(displayMetrics);
        int displayWidth = displayMetrics.widthPixels;
        int displayHeight = displayMetrics.heightPixels;
        int displayDensity = displayMetrics.densityDpi;
        Timber.d("Display size: %s x %s @ %s", displayWidth, displayHeight, displayDensity);

        Configuration configuration = context.getResources().getConfiguration();
        boolean isLandscape = configuration.orientation == ORIENTATION_LANDSCAPE;
        Timber.d("Display landscape: %s", isLandscape);

        // Get the best camera profile available. We assume MediaRecorder supports the highest.
        CamcorderProfile camcorderProfile = CamcorderProfile.get(CamcorderProfile.QUALITY_HIGH);

        int cameraWidth = camcorderProfile != null ? camcorderProfile.videoFrameWidth : -1;
        int cameraHeight = camcorderProfile != null ? camcorderProfile.videoFrameHeight : -1;
        Timber.d("Camera size: %s x %s", cameraWidth, cameraHeight);

        int sizePercentage = videoSizePercentage.get();
        Timber.d("Size percentage: %s", sizePercentage);

        return calculateRecordingInfo(displayWidth, displayHeight, displayDensity, isLandscape,
                cameraWidth, cameraHeight, sizePercentage);
    }

    private void startRecording() {

        projection = projectionManager.getMediaProjection(resultCode, data);

        initRtspClient();

        this.session.setmMediaProjection(projection);

        toggleStreaming();

        running = true;

        listener.onStart();
    }

    private void toggleStreaming() {
        if (!mClient.isStreaming()) {
            // Start camera preview
            session.startPreview();
//            session.start();
            // Start video stream
            mClient.startStream();
        }
    }

    private void initRtspClient() {
        // Configures the SessionBuilder
        session = SessionBuilderNew.getInstance()
                .setContext(this.context)
                .setAudioEncoder(SessionBuilder.AUDIO_NONE)
                .setVideoEncoder(SessionBuilder.VIDEO_H264)
                .setProjection(projection)
                .setRecordingInfo(getRecordingInfo())
                .setPreviewOrientation(0)
                .setCallback(this).build();

        // Configures the RTSP client
        mClient = new RtspClientNew();
        mClient.setSession(session);
        mClient.setCallback(this);
//        mSurfaceView.setAspectRatioMode(SurfaceView.ASPECT_RATIO_PREVIEW);
        String ip, port, path;

        // We parse the URI written in the Editext
        Pattern uri = Pattern.compile("rtsp://(.+):(\\d+)/(.+)");
        Matcher m = uri.matcher(AppConfig.STREAM_URL);
        m.find();
        ip = m.group(1);
        port = m.group(2);
        path = m.group(3);

        mClient.setCredentials(AppConfig.PUBLISHER_USERNAME,
                AppConfig.PUBLISHER_PASSWORD);
        mClient.setServerAddress(ip, Integer.parseInt(port));
        mClient.setStreamPath("/" + path);
    }

    private void stopRecording() {
        Timber.d("Stopping screen recording...");

        if (!running) {
            throw new IllegalStateException("Not running.");
        }
        running = false;

        hideOverlay();

        // Stop the projection in order to flush everything to the recorder.
        session.stop();

        // Stop the recorder which writes the contents to the file.
//        recorder.stop();
        mClient.stopStream();

        listener.onStop();

        Timber.d("Screen recording stopped. Notifying media scanner of new video.");

    }


    static RecordingInfo calculateRecordingInfo(int displayWidth, int displayHeight,
                                                int displayDensity, boolean isLandscapeDevice, int cameraWidth, int cameraHeight,
                                                int sizePercentage) {
        // Scale the display size before any maximum size calculations.
        displayWidth = displayWidth * sizePercentage / 100;
        displayHeight = displayHeight * sizePercentage / 100;

        if (cameraWidth == -1 && cameraHeight == -1) {
            // No cameras. Fall back to the display size.
            return new RecordingInfo(displayWidth, displayHeight, displayDensity);
        }

        int frameWidth = isLandscapeDevice ? cameraWidth : cameraHeight;
        int frameHeight = isLandscapeDevice ? cameraHeight : cameraWidth;
        if (frameWidth >= displayWidth && frameHeight >= displayHeight) {
            // Frame can hold the entire display. Use exact values.
            return new RecordingInfo(displayWidth, displayHeight, displayDensity);
        }

        // Calculate new width or height to preserve aspect ratio.
        if (isLandscapeDevice) {
            frameWidth = displayWidth * frameHeight / displayHeight;
        } else {
            frameHeight = displayHeight * frameWidth / displayWidth;
        }
        return new RecordingInfo(frameWidth, frameHeight, displayDensity);
    }

    public void destroy() {
        if (running) {
            Timber.w("Destroyed while running!");
            stopRecording();
        }
    }
}
