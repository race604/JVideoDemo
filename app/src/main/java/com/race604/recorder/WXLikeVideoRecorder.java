package com.race604.recorder;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Environment;
import android.text.TextUtils;
import android.util.Log;

import com.race604.jvideo.JVideo;
import com.race604.utils.FileUtil;
import com.race604.views.CameraPreviewView;

import java.io.IOException;
import java.nio.ShortBuffer;

/**
 * 仿微信录像机
 *
 * @author Martin
 */
public class WXLikeVideoRecorder implements Camera.PreviewCallback, CameraPreviewView.PreviewEventListener {

    private static final String TAG = "InstantVideoRecorder";

    // 最长录制时间6秒
    private static final long MAX_RECORD_TIME = 6000;
    // 帧率
    private static final int FRAME_RATE = 30;
    // 声音采样率
    private static final int SAMPLE_AUDIO_RATE_IN_HZ = 44100;

    private final Context mContext;
    // 输出文件目录
    private final String mFolder;
    // 输出文件路径
    private String strFinalPath;
    // 图片帧宽、高
    private int imageWidth = 320;
    private int imageHeight = 240;
    // 输出视频宽、高
    private int outputWidth = 640;
    private int outputHeight = 480;

    /* audio data getting thread */
    private AudioRecord audioRecord;
    private AudioRecordRunnable audioRecordRunnable;
    private Thread audioThread;
    volatile boolean runAudioThread = true;

    private volatile JVideo recorder;
    final Object mRecordLock = new Object();

    /**
     * 录制开始时间
     */
    private long startTime;

    /**
     * 录制停止时间
     */
    private long stopTime;

    private boolean recording;

    /* The number of seconds in the continuous record loop (or 0 to disable loop). */
    final int RECORD_LENGTH = /*6*/0;
    long[] timestamps;
    ShortBuffer[] samples;
    int imagesIndex, samplesIndex;

    // 相机预览视图
    private CameraPreviewView mCameraPreviewView;

    /**
     * 帧数据处理配置
     */
    private String mFilters;

    public WXLikeVideoRecorder(Context context, String folder) {
        mContext = context;
        mFolder = folder;
    }

    public boolean isRecording() {
        return recording;
    }

    /**
     * 设置图片帧的大小
     * @param width
     * @param height
     */
    public void setFrameSize(int width, int height) {
        imageWidth = width;
        imageHeight = height;
    }

    /**
     * 设置输出视频大小
     * @param width
     * @param height
     */
    public void setOutputSize(int width, int height) {
        outputWidth = width;
        outputHeight = height;
    }

    /**
     * 获取开始时间
     * @return
     */
    public long getStartTime() {
        return startTime;
    }

    /**
     * 获取停止时间
     * @return
     */
    public long getStopTime() {
        return stopTime;
    }

    private AudioCapturer mAudioCapturer;
    private WavFileWriter mWavFileWirter;
    private static final String DEFAULT_TEST_FILE = Environment.getExternalStorageDirectory() + "/test.wav";
    //---------------------------------------
    // initialize ffmpeg_recorder
    //---------------------------------------
    private void initRecorder() {
        Log.w(TAG, "init recorder");

        RecorderParameters recorderParameters = RecorderParameters.getRecorderParameter(Constants.RESOLUTION_MEDIUM_VALUE);
        strFinalPath = FileUtil.createFilePath(mFolder, null, Long.toString(System.currentTimeMillis()));
        // 初始化时设置录像机的目标视频大小
        recorder = new JVideo();
        recorder.setVideoSize(480, 480);
        recorder.setVideoBitRate(600000);
        recorder.setVideoCompressLevel(5);
        recorder.openEncoder(strFinalPath, imageWidth, imageHeight);

        Log.i(TAG, "recorder initialize success");

        //audioRecordRunnable = new AudioRecordRunnable();
        //audioThread = new Thread(audioRecordRunnable);
        mAudioCapturer = new AudioCapturer();
        mWavFileWirter = new WavFileWriter();
        try {
            mWavFileWirter.openFile(DEFAULT_TEST_FILE, 44100, 16, 1);
        } catch (IOException e) {
            Log.e(TAG, "initRecorder: ", e);
        }

        mAudioCapturer.setOnAudioFrameCapturedListener(new AudioCapturer.OnAudioFrameCapturedListener() {
            @Override
            public void onAudioFrameCaptured(byte[] audioData) {
                synchronized (mRecordLock) {
                    long time = System.currentTimeMillis();
                    recorder.encodeAudio(audioData);
                    Log.d(TAG, "encodeAudio time = " + (System.currentTimeMillis() - time));
                    //mWavFileWirter.writeData(audioData, 0, audioData.length);
                }
            }
        });
        runAudioThread = true;
    }

    /**
     * 设置帧图像数据处理参数
     * @param filters
     */
    public void setFilters(String filters) {
        mFilters = filters;
    }

    /**
     * 生成处理配置
     * @param w 裁切宽度
     * @param h 裁切高度
     * @param x 裁切起始x坐标
     * @param y 裁切起始y坐标
     * @param transpose 图像旋转参数
     * @return 帧图像数据处理参数
     */
    public static String generateFilters(int w, int h, int x, int y, String transpose) {
        return String.format("crop=w=%d:h=%d:x=%d:y=%d,transpose=%s", w, h, x, y, transpose);
    }

    /**
     * 初始化帧过滤器
     */
    private void initFrameFilter() {
        if (TextUtils.isEmpty(mFilters)) {
            mFilters = generateFilters((int) (1f * outputHeight / outputWidth * imageHeight), imageHeight, 0, 0, "clock");
        }
    }

    /**
     * 释放帧过滤器
     */
    private void releaseFrameFilter() {
    }

    /**
     * 获取视频文件路径
     * @return
     */
    public String getFilePath() {
        return strFinalPath;
    }

    /**
     * 开始录制
     * @return
     */
    public boolean startRecording() {
        boolean started = true;
        initRecorder();
        initFrameFilter();
        try {
            startTime = System.currentTimeMillis();
            recording = true;
            //audioThread.start();
            mAudioCapturer.startCapture();
        } catch (Exception e) {
            e.printStackTrace();
            started = false;
        }

        return started;
    }

    public void stopRecording() {
        if (!recording)
            return;

        stopTime = System.currentTimeMillis();

        runAudioThread = false;
//        try {
//            audioThread.join();
//        } catch (InterruptedException e) {
//            e.printStackTrace();
//        }
//        audioRecordRunnable = null;
//        audioThread = null;
        mAudioCapturer.stopCapture();
        try {
            mWavFileWirter.closeFile();
        } catch (IOException e) {
            Log.e(TAG, "stopRecording: ", e);
        }

        if (recorder != null && recording) {

            recording = false;
            Log.v(TAG,"Finishing recording, calling stop and release on recorder");
            recorder.closeEncoder();
            recorder = null;

            // 释放帧过滤器
            releaseFrameFilter();
        }
    }

    @Override
    public void onPreviewFrame(byte[] data, Camera camera) {
        try {
            // 去掉必须录制音频的限制，可以录制无声视频
//            if (audioRecord == null || audioRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
//                startTime = System.currentTimeMillis();
//                return;
//            }
            /* get video data */

            if (recording) {
                if ((System.currentTimeMillis() - startTime) >= MAX_RECORD_TIME) {
                    stopRecording();
                    return;
                }

                long time = System.currentTimeMillis();
                synchronized (mRecordLock) {
                    recorder.encodeVideo(data);
                }
                Log.d(TAG, "encodeVideo time = " + (System.currentTimeMillis() - time));
            }
        } finally {
            camera.addCallbackBuffer(data);
        }
    }

    /**
     * 设置相机预览视图
     * @param cameraPreviewView
     */
    public void setCameraPreviewView(CameraPreviewView cameraPreviewView) {
        mCameraPreviewView = cameraPreviewView;
        mCameraPreviewView.addPreviewEventListener(this);
        mCameraPreviewView.setViewWHRatio(1f * outputWidth / outputHeight);
    }

    @Override
    public void onPrePreviewStart() {
        Camera camera = mCameraPreviewView.getCamera();
        Camera.Parameters parameters = camera.getParameters();
        Camera.Size size = parameters.getPreviewSize();
        // 设置Recorder处理的的图像帧大小
        setFrameSize(size.width, size.height);

        camera.setPreviewCallbackWithBuffer(this);
        camera.addCallbackBuffer(new byte[size.width * size.height * ImageFormat.getBitsPerPixel(parameters.getPreviewFormat()) / 8]);
    }

    @Override
    public void onPreviewStarted() {
    }

    @Override
    public void onPreviewFailed() {
    }

    @Override
    public void onAutoFocusComplete(boolean success) {
    }

    //---------------------------------------------
    // audio thread, gets and encodes audio data
    //---------------------------------------------

    class AudioRecordRunnable implements Runnable {

        @Override
        public void run() {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);

            // Audio
            int bufferSize;
            byte[] audioData;
            int bufferReadResult;

            bufferSize = AudioRecord.getMinBufferSize(SAMPLE_AUDIO_RATE_IN_HZ,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_AUDIO_RATE_IN_HZ,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);

            if (RECORD_LENGTH > 0) {
                samplesIndex = 0;
                samples = new ShortBuffer[RECORD_LENGTH * SAMPLE_AUDIO_RATE_IN_HZ * 2 / bufferSize + 1];
                for (int i = 0; i < samples.length; i++) {
                    samples[i] = ShortBuffer.allocate(bufferSize);
                }
            } else {
                audioData = new byte[bufferSize];
            }
            final String DEFAULT_TEST_FILE = Environment.getExternalStorageDirectory() + "/test.wav";
            WavFileWriter wavFileWriter = new WavFileWriter();
            try {
                wavFileWriter.openFile(DEFAULT_TEST_FILE, SAMPLE_AUDIO_RATE_IN_HZ, 16, AudioFormat.CHANNEL_IN_MONO);
            } catch (IOException e) {
                Log.e(TAG, "open WavFileWriter error: ", e);
                return;
            }

            Log.d(TAG, "audioRecord.startRecording()");
            audioRecord.startRecording();

            /* ffmpeg_audio encoding loop */
            while (runAudioThread) {
                if (RECORD_LENGTH > 0) {
                    //audioData = samples[samplesIndex++ % samples.length];
                    //audioData.position(0).limit(0);
                }
                //Log.v(LOG_TAG,"recording? " + recording);
                //bufferReadResult = audioRecord.read(audioData.array(), 0, audioData.capacity());
                //audioData.limit(bufferReadResult);
                bufferReadResult = audioRecord.read(audioData, 0, bufferSize);
                if (bufferReadResult > 0) {
                    Log.v(TAG,"bufferReadResult: " + bufferReadResult);
                    // If "recording" isn't true when start this thread, it never get's set according to this if statement...!!!
                    // Why?  Good question...
                    if (recording) {
                        // TODO record audio
                        //short[] data = audioData.array();
                        //Log.d(TAG, "data capacity = " + audioData.capacity() + " length = " + data.length);
                        Log.d(TAG, "read audio size: " + bufferReadResult);
                        long time = System.currentTimeMillis();
                        synchronized (mRecordLock) {
                            //recorder.encodeAudio(audioData.array());
                            wavFileWriter.writeData(audioData, 0, audioData.length);
                        }
                        Log.d(TAG, "encodeAudio time = " + (System.currentTimeMillis() - time));
                    }
                }
            }
            Log.v(TAG,"AudioThread Finished, release audioRecord");

            try {
                wavFileWriter.closeFile();
            } catch (IOException e) {
                Log.e(TAG, "Close WavFileWriter error: ", e);
            }

            /* encoding finish, release recorder */
            if (audioRecord != null) {
                audioRecord.stop();
                audioRecord.release();
                audioRecord = null;
                Log.v(TAG,"audioRecord released");
            }
        }
    }

    /**
     * 录制完成监听器
     *
     * @author Martin
     */
    public interface OnRecordCompleteListener {

        /**
         * 录制完成回调
         */
        void onRecordComplete();

    }

}
