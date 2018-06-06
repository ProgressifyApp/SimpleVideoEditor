package com.createchance.simplevideoeditor.actions;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import com.createchance.simplevideoeditor.Constants;
import com.createchance.simplevideoeditor.Logger;
import com.createchance.simplevideoeditor.VideoUtil;
import com.createchance.simplevideoeditor.WorkRunner;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * ${DESC}
 *
 * @author gaochao1-iri
 * @date 25/03/2018
 */

public class VideoMergeAction extends AbstractAction {
    private static final String TAG = "VideoMergeAction";

    private List<File> mMergeFiles = new ArrayList<>();
    private MergeWorker mMergeWorker;

    private VideoMergeAction() {
        super(Constants.ACTION_MERGE_VIDEOS);
    }

    public List<File> getInputFiles() {
        return mMergeFiles;
    }

    @Override
    public void start(File inputFile) {
        super.start(inputFile);
        onStarted();
        mMergeWorker = new MergeWorker();

        WorkRunner.addTaskToBackground(mMergeWorker);
    }

    public static class Builder {
        private VideoMergeAction mergeAction = new VideoMergeAction();

        public Builder merge(File video) {
            mergeAction.mMergeFiles.add(video);

            return this;
        }

        public VideoMergeAction build() {
            return mergeAction;
        }
    }

    private class MergeWorker implements Runnable {

        MediaMuxer mediaMuxer;
        MediaFormat videoFormat;
        MediaFormat audioFormat;
        int outVideoTrackId, outAudioTrackId;
        ByteBuffer byteBuffer = ByteBuffer.allocate(512 * 1024);
        long ptsOffset;

        @Override
        public void run() {
            try {
                if (checkRational()) {
                    prepare();
                    merge();
                } else {
                    Logger.e(TAG, "Action params error.");
                    onFailed();
                }
            } catch (IOException e) {
                e.printStackTrace();
                onFailed();
            } finally {
                release();
            }
        }

        private boolean checkRational() {
            return mInputFile != null &&
                    mInputFile.exists() &&
                    mInputFile.isFile() &&
                    mOutputFile != null &&
                    mMergeFiles.size() > 0;

        }

        private void prepare() throws IOException {
            mediaMuxer = new MediaMuxer(mOutputFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            mediaMuxer.setOrientationHint(VideoUtil.getVideoRotation(mInputFile));
        }

        private void merge() throws IOException {
            // 首先找到视频列表中的视频媒体信息
            // TODO: 目前是匹配第一个找到的媒体信息
            for (File video : mMergeFiles) {
                MediaExtractor mediaExtractor = new MediaExtractor();
                mediaExtractor.setDataSource(video.getAbsolutePath());
                for (int i = 0; i < mediaExtractor.getTrackCount(); i++) {
                    MediaFormat format = mediaExtractor.getTrackFormat(i);
                    String mime = format.getString(MediaFormat.KEY_MIME);
                    if (mime.startsWith("video") && videoFormat == null) {
                        videoFormat = format;
                    } else if (mime.startsWith("audio") && audioFormat == null) {
                        audioFormat = format;
                    }

                    if (videoFormat != null && audioFormat != null) {
                        break;
                    }
                }
            }

            if (videoFormat == null && audioFormat == null) {
                Log.e(TAG, "merge, no format found!!!");
                return;
            }

            if (videoFormat != null) {
                outVideoTrackId = mediaMuxer.addTrack(videoFormat);
            }
            if (audioFormat != null) {
                outAudioTrackId = mediaMuxer.addTrack(audioFormat);
            }

            mediaMuxer.start();

            for (File video : mMergeFiles) {
                MediaExtractor videoExtractor = new MediaExtractor();
                MediaExtractor audioExtractor = new MediaExtractor();
                videoExtractor.setDataSource(video.getAbsolutePath());
                audioExtractor.setDataSource(video.getAbsolutePath());
                long videoPts = 0;
                long audioPts = 0;

                int inVideoTrackId = -1;
                int inAudioTrackId = -1;
                for (int i = 0; i < videoExtractor.getTrackCount(); i++) {
                    MediaFormat mediaFormat = videoExtractor.getTrackFormat(i);
                    String mime = mediaFormat.getString(MediaFormat.KEY_MIME);
                    if (mime.startsWith("audio")) {
                        inAudioTrackId = i;
                    } else if (mime.startsWith("video")) {
                        inVideoTrackId = i;
                    }
                }

                if (inVideoTrackId != -1) {
                    videoExtractor.selectTrack(inVideoTrackId);
                }
                if (inAudioTrackId != -1) {
                    audioExtractor.selectTrack(inAudioTrackId);
                }

                Log.d(TAG, "++++++++++++++++++++++++++++++++++++++merge start, file: " + video);

                while (true) {
                    if (inVideoTrackId == -1 && inAudioTrackId == -1) {
                        break;
                    }

                    if (inVideoTrackId != -1) {
                        int sampleSize = videoExtractor.readSampleData(byteBuffer, 0);
                        if (sampleSize > 0) {
                            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                            info.offset = 0;
                            info.size = sampleSize;
                            videoPts = videoExtractor.getSampleTime();
                            info.presentationTimeUs = ptsOffset + videoPts;
                            info.flags = MediaCodec.BUFFER_FLAG_SYNC_FRAME;
                            mediaMuxer.writeSampleData(outVideoTrackId, byteBuffer, info);
                            videoExtractor.advance();
                        } else {
                            inVideoTrackId = -1;
                        }
                    }

                    byteBuffer.clear();

                    if (inAudioTrackId != -1) {
                        int sampleSize = audioExtractor.readSampleData(byteBuffer, 0);
                        if (sampleSize > 0) {
                            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                            info.offset = 0;
                            info.size = sampleSize;
                            audioPts = audioExtractor.getSampleTime();
                            info.presentationTimeUs = ptsOffset + audioPts;
                            info.flags = MediaCodec.BUFFER_FLAG_SYNC_FRAME;
                            mediaMuxer.writeSampleData(outAudioTrackId, byteBuffer, info);
                            audioExtractor.advance();
                        } else {
                            inAudioTrackId = -1;
                        }
                    }
                }

                ptsOffset += videoPts > audioPts ? videoPts : audioPts;

                audioExtractor.release();
                videoExtractor.release();

                Log.d(TAG, "++++++++++++++++++++++++++++++++++++++merge, finish one file: " + video);
            }

            Log.d(TAG, "###############################################merge done!");

            onSucceeded();
        }

        private void release() {
            if (mediaMuxer != null) {
                mediaMuxer.stop();
                mediaMuxer.release();
            }
        }
    }
}
