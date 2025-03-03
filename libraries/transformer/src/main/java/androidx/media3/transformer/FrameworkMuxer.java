/*
 * Copyright 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.transformer;

import static androidx.media3.common.util.Assertions.checkNotNull;
import static androidx.media3.common.util.Util.SDK_INT;
import static androidx.media3.common.util.Util.castNonNull;

import android.annotation.SuppressLint;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.ParcelFileDescriptor;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.MediaFormatUtil;
import androidx.media3.common.util.Util;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;

/** Muxer implementation that uses a {@link MediaMuxer}. */
/* package */ final class FrameworkMuxer implements Muxer {

  public static final class Factory implements Muxer.Factory {
    @Override
    public FrameworkMuxer create(String path, String outputMimeType) throws IOException {
      MediaMuxer mediaMuxer = new MediaMuxer(path, mimeTypeToMuxerOutputFormat(outputMimeType));
      return new FrameworkMuxer(mediaMuxer);
    }

    @RequiresApi(26)
    @Override
    public FrameworkMuxer create(ParcelFileDescriptor parcelFileDescriptor, String outputMimeType)
        throws IOException {
      MediaMuxer mediaMuxer =
          new MediaMuxer(
              parcelFileDescriptor.getFileDescriptor(),
              mimeTypeToMuxerOutputFormat(outputMimeType));
      return new FrameworkMuxer(mediaMuxer);
    }

    @Override
    public boolean supportsOutputMimeType(String mimeType) {
      try {
        mimeTypeToMuxerOutputFormat(mimeType);
      } catch (IllegalArgumentException e) {
        return false;
      }
      return true;
    }

    @Override
    public boolean supportsSampleMimeType(
        @Nullable String sampleMimeType, String containerMimeType) {
      // MediaMuxer supported sample formats are documented in MediaMuxer.addTrack(MediaFormat).
      boolean isAudio = MimeTypes.isAudio(sampleMimeType);
      boolean isVideo = MimeTypes.isVideo(sampleMimeType);
      if (containerMimeType.equals(MimeTypes.VIDEO_MP4)) {
        if (isVideo) {
          return MimeTypes.VIDEO_H263.equals(sampleMimeType)
              || MimeTypes.VIDEO_H264.equals(sampleMimeType)
              || MimeTypes.VIDEO_MP4V.equals(sampleMimeType)
              || (Util.SDK_INT >= 24 && MimeTypes.VIDEO_H265.equals(sampleMimeType));
        } else if (isAudio) {
          return MimeTypes.AUDIO_AAC.equals(sampleMimeType)
              || MimeTypes.AUDIO_AMR_NB.equals(sampleMimeType)
              || MimeTypes.AUDIO_AMR_WB.equals(sampleMimeType);
        }
      } else if (containerMimeType.equals(MimeTypes.VIDEO_WEBM) && SDK_INT >= 21) {
        if (isVideo) {
          return MimeTypes.VIDEO_VP8.equals(sampleMimeType)
              || (Util.SDK_INT >= 24 && MimeTypes.VIDEO_VP9.equals(sampleMimeType));
        } else if (isAudio) {
          return MimeTypes.AUDIO_VORBIS.equals(sampleMimeType);
        }
      }
      return false;
    }
  }

  private final MediaMuxer mediaMuxer;
  private final MediaCodec.BufferInfo bufferInfo;

  private boolean isStarted;

  private FrameworkMuxer(MediaMuxer mediaMuxer) {
    this.mediaMuxer = mediaMuxer;
    bufferInfo = new MediaCodec.BufferInfo();
  }

  @Override
  public int addTrack(Format format) {
    String sampleMimeType = checkNotNull(format.sampleMimeType);
    MediaFormat mediaFormat;
    if (MimeTypes.isAudio(sampleMimeType)) {
      mediaFormat =
          MediaFormat.createAudioFormat(
              castNonNull(sampleMimeType), format.sampleRate, format.channelCount);
    } else {
      mediaFormat =
          MediaFormat.createVideoFormat(castNonNull(sampleMimeType), format.width, format.height);
      mediaMuxer.setOrientationHint(format.rotationDegrees);
    }
    MediaFormatUtil.setCsdBuffers(mediaFormat, format.initializationData);
    return mediaMuxer.addTrack(mediaFormat);
  }

  @SuppressLint("WrongConstant") // C.BUFFER_FLAG_KEY_FRAME equals MediaCodec.BUFFER_FLAG_KEY_FRAME.
  @Override
  public void writeSampleData(
      int trackIndex, ByteBuffer data, boolean isKeyFrame, long presentationTimeUs) {
    if (!isStarted) {
      isStarted = true;
      mediaMuxer.start();
    }
    int offset = data.position();
    int size = data.limit() - offset;
    int flags = isKeyFrame ? C.BUFFER_FLAG_KEY_FRAME : 0;
    bufferInfo.set(offset, size, presentationTimeUs, flags);
    mediaMuxer.writeSampleData(trackIndex, data, bufferInfo);
  }

  @Override
  public void release(boolean forCancellation) {
    if (!isStarted) {
      mediaMuxer.release();
      return;
    }

    isStarted = false;
    try {
      mediaMuxer.stop();
    } catch (IllegalStateException e) {
      if (SDK_INT < 30) {
        // Set the muxer state to stopped even if mediaMuxer.stop() failed so that
        // mediaMuxer.release() doesn't attempt to stop the muxer and therefore doesn't throw the
        // same exception without releasing its resources. This is already implemented in MediaMuxer
        // from API level 30.
        try {
          Field muxerStoppedStateField = MediaMuxer.class.getDeclaredField("MUXER_STATE_STOPPED");
          muxerStoppedStateField.setAccessible(true);
          int muxerStoppedState = castNonNull((Integer) muxerStoppedStateField.get(mediaMuxer));
          Field muxerStateField = MediaMuxer.class.getDeclaredField("mState");
          muxerStateField.setAccessible(true);
          muxerStateField.set(mediaMuxer, muxerStoppedState);
        } catch (Exception reflectionException) {
          // Do nothing.
        }
      }
      // It doesn't matter that stopping the muxer throws if the transformation is being cancelled.
      if (!forCancellation) {
        throw e;
      }
    } finally {
      mediaMuxer.release();
    }
  }

  /**
   * Converts a {@link MimeTypes MIME type} into a {@link MediaMuxer.OutputFormat MediaMuxer output
   * format}.
   *
   * @param mimeType The {@link MimeTypes MIME type} to convert.
   * @return The corresponding {@link MediaMuxer.OutputFormat MediaMuxer output format}.
   * @throws IllegalArgumentException If the {@link MimeTypes MIME type} is not supported as output
   *     format.
   */
  private static int mimeTypeToMuxerOutputFormat(String mimeType) {
    if (mimeType.equals(MimeTypes.VIDEO_MP4)) {
      return MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4;
    } else if (SDK_INT >= 21 && mimeType.equals(MimeTypes.VIDEO_WEBM)) {
      return MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM;
    } else {
      throw new IllegalArgumentException("Unsupported output MIME type: " + mimeType);
    }
  }
}
