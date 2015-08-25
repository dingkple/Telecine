/*
 * Copyright (C) 2011-2014 GUIGUI Simon, fyhertz@gmail.com
 * 
 * This file is part of libstreaming (https://github.com/fyhertz/libstreaming)
 * 
 * Spydroid is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This source code is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this source code; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package net.majorkernelpanic.streaming;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.res.Configuration;
import android.media.CamcorderProfile;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.preference.PreferenceManager;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import net.majorkernelpanic.streaming.audio.AACStream;
import net.majorkernelpanic.streaming.audio.AMRNBStream;
import net.majorkernelpanic.streaming.audio.AudioQuality;
import net.majorkernelpanic.streaming.audio.AudioStream;
import net.majorkernelpanic.streaming.gl.SurfaceView;
import net.majorkernelpanic.streaming.video.H264StreamNew;
import net.majorkernelpanic.streaming.video.RecordingInfo;
import net.majorkernelpanic.streaming.video.VideoQuality;
import net.majorkernelpanic.streaming.video.VideoStreamNew;

import java.io.IOException;

import static android.content.Context.WINDOW_SERVICE;
import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;

/**
 * Call {@link #getInstance()} to get access to the SessionBuilderNew.
 */
public class SessionBuilderNew {

    public final static String TAG = "SessionBuilderNew";

    /**
     * Can be used with {@link #setVideoEncoder}.
     */
    public final static int VIDEO_NONE = 0;

    /**
     * Can be used with {@link #setVideoEncoder}.
     */
    public final static int VIDEO_H264 = 1;

    /**
     * Can be used with {@link #setVideoEncoder}.
     */
    public final static int VIDEO_H263 = 2;

    /**
     * Can be used with {@link #setAudioEncoder}.
     */
    public final static int AUDIO_NONE = 0;

    /**
     * Can be used with {@link #setAudioEncoder}.
     */
    public final static int AUDIO_AMRNB = 3;

    /**
     * Can be used with {@link #setAudioEncoder}.
     */
    public final static int AUDIO_AAC = 5;

    // Default configuration
    private VideoQuality mVideoQuality = VideoQuality.DEFAULT_VIDEO_QUALITY;
    private AudioQuality mAudioQuality = AudioQuality.DEFAULT_AUDIO_QUALITY;
    private Context mContext;
    private int                 mVideoEncoder = VIDEO_H264;
    private int                 mAudioEncoder = AUDIO_AMRNB;
    private int                 mTimeToLive   = 64;
    private int                 mOrientation  = 0;
    private String              mOrigin       = null;
    private String              mDestination  = null;
    private SessionNew.Callback mCallback     = null;
    private MediaProjection mProjection;
    private RecordingInfo   mRecordingInfo;


    // Removes the default public constructor
    private SessionBuilderNew() {
    }

    // The SessionManager implements the singleton pattern
    private static volatile SessionBuilderNew sInstance = null;

    /**
     * Returns a reference to the {@link SessionBuilderNew}.
     *
     * @return The reference to the {@link SessionBuilderNew}
     */
    public final static SessionBuilderNew getInstance() {
        if (sInstance == null) {
            synchronized (SessionBuilderNew.class) {
                if (sInstance == null) {
                    SessionBuilderNew.sInstance = new SessionBuilderNew();
                }
            }
        }
        return sInstance;
    }

    /**
     * Creates a new {@link Session}.
     *
     * @return The new Session
     * @throws IOException
     */
    public SessionNew build() {
        SessionNew session;

        session = new SessionNew();
        session.setOrigin(mOrigin);
        session.setDestination(mDestination);
        session.setTimeToLive(mTimeToLive);
        session.setCallback(mCallback);


        switch (mAudioEncoder) {
            case AUDIO_AAC:
                AACStream stream = new AACStream();
                session.addAudioTrack(stream);
                if (mContext != null)
                    stream.setPreferences(PreferenceManager.getDefaultSharedPreferences(mContext));
                break;
            case AUDIO_AMRNB:
                session.addAudioTrack(new AMRNBStream());
                break;
        }

        H264StreamNew stream = new H264StreamNew();
        stream.setmRecordingInfo(mRecordingInfo);
        stream.setmMediaProjection(mProjection);
        if (mContext != null)
            stream.setPreferences(PreferenceManager.getDefaultSharedPreferences(mContext));
        session.addVideoTrack(stream);


        if (session.getVideoTrack() != null) {
            VideoStreamNew video = session.getVideoTrack();
            video.setVideoQuality(mVideoQuality);
            video.setPreviewOrientation(mOrientation);
            video.setDestinationPorts(5006);
        }

        if (session.getAudioTrack() != null) {
            AudioStream audio = session.getAudioTrack();
            audio.setAudioQuality(mAudioQuality);
            audio.setDestinationPorts(5004);
        }

        return session;

    }

    /**
     * Access to the context is needed for the H264Stream class to store some stuff in the SharedPreferences.
     * Note that you should pass the Application context, not the context of an Activity.
     **/
    public SessionBuilderNew setContext(Context context) {
        mContext = context;
        return this;
    }

    /**
     * Sets the destination of the session.
     */
    public SessionBuilderNew setDestination(String destination) {
        mDestination = destination;
        return this;
    }

    /**
     * Sets the origin of the session. It appears in the SDP of the session.
     */
    public SessionBuilderNew setOrigin(String origin) {
        mOrigin = origin;
        return this;
    }

    /**
     * Sets the video stream quality.
     */
    public SessionBuilderNew setVideoQuality(VideoQuality quality) {
        mVideoQuality = quality.clone();
        return this;
    }

    /**
     * Sets the audio encoder.
     */
    public SessionBuilderNew setAudioEncoder(int encoder) {
        mAudioEncoder = encoder;
        return this;
    }

    /**
     * Sets the audio quality.
     */
    public SessionBuilderNew setAudioQuality(AudioQuality quality) {
        mAudioQuality = quality.clone();
        return this;
    }

    /**
     * Sets the default video encoder.
     */
    public SessionBuilderNew setVideoEncoder(int encoder) {
        mVideoEncoder = encoder;
        return this;
    }

    public SessionBuilderNew setTimeToLive(int ttl) {
        mTimeToLive = ttl;
        return this;
    }

    public SessionBuilderNew setRecordingInfo(RecordingInfo recordingInfo) {
        mRecordingInfo = recordingInfo;
        return this;
    }


    /**
     * Sets the orientation of the preview.
     *
     * @param orientation The orientation of the preview
     */
    public SessionBuilderNew setPreviewOrientation(int orientation) {
        mOrientation = orientation;
        return this;
    }

    public SessionBuilderNew setCallback(SessionNew.Callback callback) {
        mCallback = callback;
        return this;
    }

    public SessionBuilderNew setProjection(MediaProjection projection) {
        mProjection = projection;
        return this;
    }

    /**
     * Returns the context set with {@link #setContext(Context)}
     */
    public Context getContext() {
        return mContext;
    }

    /**
     * Returns the destination ip address set with {@link #setDestination(String)}.
     */
    public String getDestination() {
        return mDestination;
    }

    /**
     * Returns the origin ip address set with {@link #setOrigin(String)}.
     */
    public String getOrigin() {
        return mOrigin;
    }

    /**
     * Returns the audio encoder set with {@link #setAudioEncoder(int)}.
     */
    public int getAudioEncoder() {
        return mAudioEncoder;
    }

    /**
     * Returns the video encoder set with {@link #setVideoEncoder(int)}.
     */
    public int getVideoEncoder() {
        return mVideoEncoder;
    }

    /**
     * Returns the VideoQuality set with {@link #setVideoQuality(VideoQuality)}.
     */
    public VideoQuality getVideoQuality() {
        return mVideoQuality;
    }

    /**
     * Returns the AudioQuality set with {@link #setAudioQuality(AudioQuality)}.
     */
    public AudioQuality getAudioQuality() {
        return mAudioQuality;
    }


    /**
     * Returns the time to live set with {@link #setTimeToLive(int)}.
     */
    public int getTimeToLive() {
        return mTimeToLive;
    }


    /**
     * Returns a new {@link SessionBuilderNew} with the same configuration.
     */
    public SessionBuilderNew clone() {
        return new SessionBuilderNew()
                .setDestination(mDestination)
                .setOrigin(mOrigin)
                .setPreviewOrientation(mOrientation)
                .setVideoQuality(mVideoQuality)
                .setVideoEncoder(mVideoEncoder)
                .setTimeToLive(mTimeToLive)
                .setAudioEncoder(mAudioEncoder)
                .setAudioQuality(mAudioQuality)
                .setContext(mContext)
                .setCallback(mCallback);
    }


}
