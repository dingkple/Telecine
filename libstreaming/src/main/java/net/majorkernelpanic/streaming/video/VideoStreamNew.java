package net.majorkernelpanic.streaming.video;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.SharedPreferences;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;

import net.majorkernelpanic.streaming.MediaStream;
import net.majorkernelpanic.streaming.exceptions.ConfNotSupportedException;
import net.majorkernelpanic.streaming.gl.SurfaceView;
import net.majorkernelpanic.streaming.hw.EncoderDebugger;
import net.majorkernelpanic.streaming.rtp.MediaCodecInputStream;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;

public abstract class VideoStreamNew extends MediaStream {

    protected final static String TAG = "VideoStreamNew";

    protected VideoQuality      mRequestedQuality     = VideoQuality.DEFAULT_VIDEO_QUALITY.clone();
    protected VideoQuality      mQuality              = mRequestedQuality.clone();
    protected SharedPreferences mSettings             = null;
    protected int               mVideoEncoder         = 0;
    protected int               mRequestedOrientation = 0, mOrientation = 0;

    protected boolean mUpdated = false;

    protected String        mMimeType;
    private   RecordingInfo mRecordingInfo;

    public void setmMediaProjection(MediaProjection mMediaProjection) {
        this.mMediaProjection = mMediaProjection;
    }

    private MediaProjection mMediaProjection;
    private VirtualDisplay  mVirtualPlay;


    public VideoStreamNew() {
        super();
    }

    /**
     * Stops the preview.
     */
    public synchronized void stopPreview() {
        stop();
    }


    /**
     * Sets the orientation of the preview.
     *
     * @param orientation The orientation of the preview
     */
    public void setPreviewOrientation(int orientation) {
        mRequestedOrientation = orientation;
        mUpdated = false;
    }


    /**
     * Sets the configuration of the stream. You can call this method at any time
     * and changes will take effect next time you call {@link #configure()}.
     *
     * @param videoQuality Quality of the stream
     */
    public void setVideoQuality(VideoQuality videoQuality) {
        if (!mRequestedQuality.equals(videoQuality)) {
            mRequestedQuality = videoQuality.clone();
            mUpdated = false;
        }
    }

    /**
     * Configures the stream. You need to call this before calling {@link #getSessionDescription()}
     * to apply your configuration of the stream.
     */
    public synchronized void configure() throws IllegalStateException, IOException {
        super.configure();
        mOrientation = mRequestedOrientation;
    }

    /**
     * Stops the stream.
     */
    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    public synchronized void stop() {
        if (mMediaProjection != null)
            mMediaProjection.stop();
        if (mVirtualPlay != null)
            mVirtualPlay.release();
        super.stop();

    }


    /**
     * Starts the stream.
     * This will also open the camera and display the preview
     */
    public synchronized void start() throws IllegalStateException, IOException {
        super.start();
        Log.d(TAG, "Stream configuration: FPS: " + mQuality.framerate + " Width: " + mQuality.resX + " Height: " + mQuality.resY);
    }


    /**
     * Video encoding is done by a MediaCodec.
     * But here we will use the buffer-to-surface method
     */
    @SuppressLint({"InlinedApi", "NewApi"})
    protected void encodeWithMediaCodec() throws RuntimeException, IOException {

        Log.d(TAG, "Video encoded using the MediaCodec API with a surface");

        EncoderDebugger debugger = EncoderDebugger.debug(mSettings, mQuality.resX, mQuality.resY);

        mMediaCodec = MediaCodec.createByCodecName(debugger.getEncoderName());
        MediaFormat mediaFormat = MediaFormat.createVideoFormat("video/avc", mQuality.resX, mQuality.resY);
        mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, mQuality.bitrate);
        mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, mQuality.framerate);
        mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 3);

        // configure from screen_sream_mirror
        mediaFormat.setInteger(MediaFormat.KEY_BITRATE_MODE, 2);

        mMediaCodec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);

        setProjector();

        mMediaCodec.start();

        // The packetizer encapsulates the bit stream in an RTP stream and send it over the network
        mPacketizer.setInputStream(new MediaCodecInputStream(mMediaCodec));
        mPacketizer.start();

        mStreaming = true;

    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    @Override
    protected void encodeWithMediaRecorder() throws IOException {
        Log.d(TAG, "Video encoded using the MediaRecorder API");

        // We need a local socket to forward data output by the camera to the packetizer
        createSockets();

        try {
            mMediaRecorder = new MediaRecorder();
            mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
            mMediaRecorder.setVideoEncoder(mVideoEncoder);
            mMediaRecorder.setVideoSize(mRequestedQuality.resX, mRequestedQuality.resY);
            mMediaRecorder.setVideoFrameRate(mRequestedQuality.framerate);

            // The bandwidth actually consumed is often above what was requested
            mMediaRecorder.setVideoEncodingBitRate((int) (mRequestedQuality.bitrate * 0.8));
//            mMediaRecorder.setVideoEncodingBitRate((int) 8 * 1000 * 1000);

            // We write the output of the camera in a local socket instead of a file !
            // This one little trick makes streaming feasible quiet simply: data from the camera
            // can then be manipulated at the other end of the socket
            FileDescriptor fd = null;
            if (sPipeApi == PIPE_API_PFD) {
                fd = mParcelWrite.getFileDescriptor();
            } else {
                fd = mSender.getFileDescriptor();
            }
            mMediaRecorder.setOutputFile(fd);

            mMediaRecorder.prepare();

            setProjector();

            mMediaRecorder.start();

        } catch (Exception e) {
            throw new ConfNotSupportedException(e.getMessage());
        }

        InputStream is = null;

        if (sPipeApi == PIPE_API_PFD) {
            is = new ParcelFileDescriptor.AutoCloseInputStream(mParcelRead);
        } else {
            is = mReceiver.getInputStream();
        }

        // This will skip the MPEG4 header if this step fails we can't stream anything :(
        try {
            byte buffer[] = new byte[4];
            // Skip all atoms preceding mdat atom
            while (!Thread.interrupted()) {
                while (is.read() != 'm') ;
                is.read(buffer, 0, 3);
                if (buffer[0] == 'd' && buffer[1] == 'a' && buffer[2] == 't') break;
            }
        } catch (IOException e) {
            Log.e(TAG, "Couldn't skip mp4 header :/");
            stop();
            throw e;
        }

        // The packetizer encapsulates the bit stream in an RTP stream and send it over the network
        mPacketizer.setInputStream(is);
        mPacketizer.start();

        mStreaming = true;

    }

    public RecordingInfo getmRecordingInfo() {
        return mRecordingInfo;
    }

    public void setmRecordingInfo(RecordingInfo mRecordingInfo) {
        this.mRecordingInfo = mRecordingInfo;
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    public void setProjector() {

        Surface surface;
        if (mMode != MODE_MEDIARECORDER_API) {
            surface = mMediaCodec.createInputSurface();
        } else {
            surface = mMediaRecorder.getSurface();
        }

        if (mVirtualPlay != null)
            mVirtualPlay.release();

        mVirtualPlay = mMediaProjection.createVirtualDisplay(TAG + "display",
                mRequestedQuality.resX, mRequestedQuality.resY, 1,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null);
    }


    public abstract String getSessionDescription() throws IllegalStateException;
}
