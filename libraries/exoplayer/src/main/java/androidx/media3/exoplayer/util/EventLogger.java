/*
 * Copyright (C) 2016 The Android Open Source Project
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
package androidx.media3.exoplayer.util;

import static androidx.media3.common.util.Util.getFormatSupportString;
import static androidx.media3.exoplayer.RendererCapabilities.DECODER_SUPPORT_FALLBACK;
import static androidx.media3.exoplayer.RendererCapabilities.HARDWARE_ACCELERATION_SUPPORTED;
import static androidx.media3.exoplayer.RendererCapabilities.getDecoderSupport;
import static androidx.media3.exoplayer.RendererCapabilities.getFormatSupport;
import static androidx.media3.exoplayer.RendererCapabilities.getHardwareAccelerationSupport;
import static java.lang.Math.min;

import android.os.SystemClock;
import android.text.TextUtils;
import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Metadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.Player.PlaybackSuppressionReason;
import androidx.media3.common.Timeline;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.TrackGroupArray;
import androidx.media3.common.TrackSelection;
import androidx.media3.common.TrackSelectionArray;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DecoderCounters;
import androidx.media3.exoplayer.DecoderReuseEvaluation;
import androidx.media3.exoplayer.RendererCapabilities;
import androidx.media3.exoplayer.RendererCapabilities.AdaptiveSupport;
import androidx.media3.exoplayer.RendererCapabilities.Capabilities;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.drm.DrmSession;
import androidx.media3.exoplayer.source.LoadEventInfo;
import androidx.media3.exoplayer.source.MediaLoadData;
import androidx.media3.exoplayer.trackselection.MappingTrackSelector;
import androidx.media3.exoplayer.trackselection.MappingTrackSelector.MappedTrackInfo;
import java.io.IOException;
import java.text.NumberFormat;
import java.util.Locale;

/** Logs events from {@link Player} and other core components using {@link Log}. */
@SuppressWarnings("UngroupedOverloads")
@UnstableApi
public class EventLogger implements AnalyticsListener {

  private static final String DEFAULT_TAG = "EventLogger";
  private static final int MAX_TIMELINE_ITEM_LINES = 3;
  private static final NumberFormat TIME_FORMAT;

  static {
    TIME_FORMAT = NumberFormat.getInstance(Locale.US);
    TIME_FORMAT.setMinimumFractionDigits(2);
    TIME_FORMAT.setMaximumFractionDigits(2);
    TIME_FORMAT.setGroupingUsed(false);
  }

  @Nullable private final MappingTrackSelector trackSelector;
  private final String tag;
  private final Timeline.Window window;
  private final Timeline.Period period;
  private final long startTimeMs;

  /**
   * Creates event logger.
   *
   * @param trackSelector The mapping track selector used by the player. May be null if detailed
   *     logging of track mapping is not required.
   */
  public EventLogger(@Nullable MappingTrackSelector trackSelector) {
    this(trackSelector, DEFAULT_TAG);
  }

  /**
   * Creates event logger.
   *
   * @param trackSelector The mapping track selector used by the player. May be null if detailed
   *     logging of track mapping is not required.
   * @param tag The tag used for logging.
   */
  public EventLogger(@Nullable MappingTrackSelector trackSelector, String tag) {
    this.trackSelector = trackSelector;
    this.tag = tag;
    window = new Timeline.Window();
    period = new Timeline.Period();
    startTimeMs = SystemClock.elapsedRealtime();
  }

  // AnalyticsListener

  @Override
  public void onIsLoadingChanged(EventTime eventTime, boolean isLoading) {
    logd(eventTime, "loading", Boolean.toString(isLoading));
  }

  @Override
  public void onPlaybackStateChanged(EventTime eventTime, @Player.State int state) {
    logd(eventTime, "state", getStateString(state));
  }

  @Override
  public void onPlayWhenReadyChanged(
      EventTime eventTime, boolean playWhenReady, @Player.PlayWhenReadyChangeReason int reason) {
    logd(
        eventTime,
        "playWhenReady",
        playWhenReady + ", " + getPlayWhenReadyChangeReasonString(reason));
  }

  @Override
  public void onPlaybackSuppressionReasonChanged(
      EventTime eventTime, @PlaybackSuppressionReason int playbackSuppressionReason) {
    logd(
        eventTime,
        "playbackSuppressionReason",
        getPlaybackSuppressionReasonString(playbackSuppressionReason));
  }

  @Override
  public void onIsPlayingChanged(EventTime eventTime, boolean isPlaying) {
    logd(eventTime, "isPlaying", Boolean.toString(isPlaying));
  }

  @Override
  public void onRepeatModeChanged(EventTime eventTime, @Player.RepeatMode int repeatMode) {
    logd(eventTime, "repeatMode", getRepeatModeString(repeatMode));
  }

  @Override
  public void onShuffleModeChanged(EventTime eventTime, boolean shuffleModeEnabled) {
    logd(eventTime, "shuffleModeEnabled", Boolean.toString(shuffleModeEnabled));
  }

  @Override
  public void onPositionDiscontinuity(
      EventTime eventTime,
      Player.PositionInfo oldPosition,
      Player.PositionInfo newPosition,
      @Player.DiscontinuityReason int reason) {
    StringBuilder builder = new StringBuilder();
    builder
        .append("reason=")
        .append(getDiscontinuityReasonString(reason))
        .append(", PositionInfo:old [")
        .append("mediaItem=")
        .append(oldPosition.mediaItemIndex)
        .append(", period=")
        .append(oldPosition.periodIndex)
        .append(", pos=")
        .append(oldPosition.positionMs);
    if (oldPosition.adGroupIndex != C.INDEX_UNSET) {
      builder
          .append(", contentPos=")
          .append(oldPosition.contentPositionMs)
          .append(", adGroup=")
          .append(oldPosition.adGroupIndex)
          .append(", ad=")
          .append(oldPosition.adIndexInAdGroup);
    }
    builder
        .append("], PositionInfo:new [")
        .append("mediaItem=")
        .append(newPosition.mediaItemIndex)
        .append(", period=")
        .append(newPosition.periodIndex)
        .append(", pos=")
        .append(newPosition.positionMs);
    if (newPosition.adGroupIndex != C.INDEX_UNSET) {
      builder
          .append(", contentPos=")
          .append(newPosition.contentPositionMs)
          .append(", adGroup=")
          .append(newPosition.adGroupIndex)
          .append(", ad=")
          .append(newPosition.adIndexInAdGroup);
    }
    builder.append("]");
    logd(eventTime, "positionDiscontinuity", builder.toString());
  }

  @Override
  public void onPlaybackParametersChanged(
      EventTime eventTime, PlaybackParameters playbackParameters) {
    logd(eventTime, "playbackParameters", playbackParameters.toString());
  }

  @Override
  public void onTimelineChanged(EventTime eventTime, @Player.TimelineChangeReason int reason) {
    int periodCount = eventTime.timeline.getPeriodCount();
    int windowCount = eventTime.timeline.getWindowCount();
    logd(
        "timeline ["
            + getEventTimeString(eventTime)
            + ", periodCount="
            + periodCount
            + ", windowCount="
            + windowCount
            + ", reason="
            + getTimelineChangeReasonString(reason));
    for (int i = 0; i < min(periodCount, MAX_TIMELINE_ITEM_LINES); i++) {
      eventTime.timeline.getPeriod(i, period);
      logd("  " + "period [" + getTimeString(period.getDurationMs()) + "]");
    }
    if (periodCount > MAX_TIMELINE_ITEM_LINES) {
      logd("  ...");
    }
    for (int i = 0; i < min(windowCount, MAX_TIMELINE_ITEM_LINES); i++) {
      eventTime.timeline.getWindow(i, window);
      logd(
          "  "
              + "window ["
              + getTimeString(window.getDurationMs())
              + ", seekable="
              + window.isSeekable
              + ", dynamic="
              + window.isDynamic
              + "]");
    }
    if (windowCount > MAX_TIMELINE_ITEM_LINES) {
      logd("  ...");
    }
    logd("]");
  }

  @Override
  public void onMediaItemTransition(
      EventTime eventTime, @Nullable MediaItem mediaItem, int reason) {
    logd(
        "mediaItem ["
            + getEventTimeString(eventTime)
            + ", reason="
            + getMediaItemTransitionReasonString(reason)
            + "]");
  }

  @Override
  public void onPlayerError(EventTime eventTime, PlaybackException error) {
    loge(eventTime, "playerFailed", error);
  }

  @Override
  public void onTracksChanged(
      EventTime eventTime, TrackGroupArray trackGroups, TrackSelectionArray trackSelections) {
    MappedTrackInfo mappedTrackInfo =
        trackSelector != null ? trackSelector.getCurrentMappedTrackInfo() : null;
    if (mappedTrackInfo == null) {
      logd(eventTime, "tracks", "[]");
      return;
    }
    logd("tracks [" + getEventTimeString(eventTime));
    // Log tracks associated to renderers.
    int rendererCount = mappedTrackInfo.getRendererCount();
    for (int rendererIndex = 0; rendererIndex < rendererCount; rendererIndex++) {
      TrackGroupArray rendererTrackGroups = mappedTrackInfo.getTrackGroups(rendererIndex);
      TrackSelection trackSelection = trackSelections.get(rendererIndex);
      if (rendererTrackGroups.length == 0) {
        logd("  " + mappedTrackInfo.getRendererName(rendererIndex) + " []");
      } else {
        logd("  " + mappedTrackInfo.getRendererName(rendererIndex) + " [");
        for (int groupIndex = 0; groupIndex < rendererTrackGroups.length; groupIndex++) {
          TrackGroup trackGroup = rendererTrackGroups.get(groupIndex);
          String adaptiveSupport =
              getAdaptiveSupportString(
                  trackGroup.length,
                  mappedTrackInfo.getAdaptiveSupport(
                      rendererIndex, groupIndex, /* includeCapabilitiesExceededTracks= */ false));
          logd("    Group:" + trackGroup.id + ", adaptive_supported=" + adaptiveSupport + " [");
          for (int trackIndex = 0; trackIndex < trackGroup.length; trackIndex++) {
            String status = getTrackStatusString(trackSelection, trackGroup, trackIndex);
            @Capabilities
            int capabilities =
                mappedTrackInfo.getCapabilities(rendererIndex, groupIndex, trackIndex);
            String formatSupport = getFormatSupportString(getFormatSupport(capabilities));
            String hardwareAccelerationSupport =
                getHardwareAccelerationSupport(capabilities) == HARDWARE_ACCELERATION_SUPPORTED
                    ? ", accelerated=YES"
                    : "";
            String decoderSupport =
                getDecoderSupport(capabilities) == DECODER_SUPPORT_FALLBACK ? ", fallback=YES" : "";
            logd(
                "      "
                    + status
                    + " Track:"
                    + trackIndex
                    + ", "
                    + Format.toLogString(trackGroup.getFormat(trackIndex))
                    + ", supported="
                    + formatSupport
                    + hardwareAccelerationSupport
                    + decoderSupport);
          }
          logd("    ]");
        }
        // Log metadata for at most one of the tracks selected for the renderer.
        if (trackSelection != null) {
          for (int selectionIndex = 0; selectionIndex < trackSelection.length(); selectionIndex++) {
            Metadata metadata = trackSelection.getFormat(selectionIndex).metadata;
            if (metadata != null) {
              logd("    Metadata [");
              printMetadata(metadata, "      ");
              logd("    ]");
              break;
            }
          }
        }
        logd("  ]");
      }
    }
    // Log tracks not associated with a renderer.
    TrackGroupArray unassociatedTrackGroups = mappedTrackInfo.getUnmappedTrackGroups();
    if (unassociatedTrackGroups.length > 0) {
      logd("  Unmapped [");
      for (int groupIndex = 0; groupIndex < unassociatedTrackGroups.length; groupIndex++) {
        logd("    Group:" + groupIndex + " [");
        TrackGroup trackGroup = unassociatedTrackGroups.get(groupIndex);
        for (int trackIndex = 0; trackIndex < trackGroup.length; trackIndex++) {
          String status = getTrackStatusString(false);
          String formatSupport = getFormatSupportString(C.FORMAT_UNSUPPORTED_TYPE);
          logd(
              "      "
                  + status
                  + " Track:"
                  + trackIndex
                  + ", "
                  + Format.toLogString(trackGroup.getFormat(trackIndex))
                  + ", supported="
                  + formatSupport);
        }
        logd("    ]");
      }
      logd("  ]");
    }
    logd("]");
  }

  @Override
  public void onMetadata(EventTime eventTime, Metadata metadata) {
    logd("metadata [" + getEventTimeString(eventTime));
    printMetadata(metadata, "  ");
    logd("]");
  }

  @Override
  public void onAudioEnabled(EventTime eventTime, DecoderCounters decoderCounters) {
    logd(eventTime, "audioEnabled");
  }

  @Override
  public void onAudioDecoderInitialized(
      EventTime eventTime, String decoderName, long initializationDurationMs) {
    logd(eventTime, "audioDecoderInitialized", decoderName);
  }

  @Override
  public void onAudioInputFormatChanged(
      EventTime eventTime, Format format, @Nullable DecoderReuseEvaluation decoderReuseEvaluation) {
    logd(eventTime, "audioInputFormat", Format.toLogString(format));
  }

  @Override
  public void onAudioUnderrun(
      EventTime eventTime, int bufferSize, long bufferSizeMs, long elapsedSinceLastFeedMs) {
    loge(
        eventTime,
        "audioTrackUnderrun",
        bufferSize + ", " + bufferSizeMs + ", " + elapsedSinceLastFeedMs,
        /* throwable= */ null);
  }

  @Override
  public void onAudioDecoderReleased(EventTime eventTime, String decoderName) {
    logd(eventTime, "audioDecoderReleased", decoderName);
  }

  @Override
  public void onAudioDisabled(EventTime eventTime, DecoderCounters decoderCounters) {
    logd(eventTime, "audioDisabled");
  }

  @Override
  public void onAudioSessionIdChanged(EventTime eventTime, int audioSessionId) {
    logd(eventTime, "audioSessionId", Integer.toString(audioSessionId));
  }

  @Override
  public void onAudioAttributesChanged(EventTime eventTime, AudioAttributes audioAttributes) {
    logd(
        eventTime,
        "audioAttributes",
        audioAttributes.contentType
            + ","
            + audioAttributes.flags
            + ","
            + audioAttributes.usage
            + ","
            + audioAttributes.allowedCapturePolicy);
  }

  @Override
  public void onSkipSilenceEnabledChanged(EventTime eventTime, boolean skipSilenceEnabled) {
    logd(eventTime, "skipSilenceEnabled", Boolean.toString(skipSilenceEnabled));
  }

  @Override
  public void onVolumeChanged(EventTime eventTime, float volume) {
    logd(eventTime, "volume", Float.toString(volume));
  }

  @Override
  public void onVideoEnabled(EventTime eventTime, DecoderCounters decoderCounters) {
    logd(eventTime, "videoEnabled");
  }

  @Override
  public void onVideoDecoderInitialized(
      EventTime eventTime, String decoderName, long initializationDurationMs) {
    logd(eventTime, "videoDecoderInitialized", decoderName);
  }

  @Override
  public void onVideoInputFormatChanged(
      EventTime eventTime, Format format, @Nullable DecoderReuseEvaluation decoderReuseEvaluation) {
    logd(eventTime, "videoInputFormat", Format.toLogString(format));
  }

  @Override
  public void onDroppedVideoFrames(EventTime eventTime, int droppedFrames, long elapsedMs) {
    logd(eventTime, "droppedFrames", Integer.toString(droppedFrames));
  }

  @Override
  public void onVideoDecoderReleased(EventTime eventTime, String decoderName) {
    logd(eventTime, "videoDecoderReleased", decoderName);
  }

  @Override
  public void onVideoDisabled(EventTime eventTime, DecoderCounters decoderCounters) {
    logd(eventTime, "videoDisabled");
  }

  @Override
  public void onRenderedFirstFrame(EventTime eventTime, Object output, long renderTimeMs) {
    logd(eventTime, "renderedFirstFrame", String.valueOf(output));
  }

  @Override
  public void onVideoSizeChanged(EventTime eventTime, VideoSize videoSize) {
    logd(eventTime, "videoSize", videoSize.width + ", " + videoSize.height);
  }

  @Override
  public void onLoadStarted(
      EventTime eventTime, LoadEventInfo loadEventInfo, MediaLoadData mediaLoadData) {
    // Do nothing.
  }

  @Override
  public void onLoadError(
      EventTime eventTime,
      LoadEventInfo loadEventInfo,
      MediaLoadData mediaLoadData,
      IOException error,
      boolean wasCanceled) {
    printInternalError(eventTime, "loadError", error);
  }

  @Override
  public void onLoadCanceled(
      EventTime eventTime, LoadEventInfo loadEventInfo, MediaLoadData mediaLoadData) {
    // Do nothing.
  }

  @Override
  public void onLoadCompleted(
      EventTime eventTime, LoadEventInfo loadEventInfo, MediaLoadData mediaLoadData) {
    // Do nothing.
  }

  @Override
  public void onBandwidthEstimate(
      EventTime eventTime, int totalLoadTimeMs, long totalBytesLoaded, long bitrateEstimate) {
    // Do nothing.
  }

  @Override
  public void onSurfaceSizeChanged(EventTime eventTime, int width, int height) {
    logd(eventTime, "surfaceSize", width + ", " + height);
  }

  @Override
  public void onUpstreamDiscarded(EventTime eventTime, MediaLoadData mediaLoadData) {
    logd(eventTime, "upstreamDiscarded", Format.toLogString(mediaLoadData.trackFormat));
  }

  @Override
  public void onDownstreamFormatChanged(EventTime eventTime, MediaLoadData mediaLoadData) {
    logd(eventTime, "downstreamFormat", Format.toLogString(mediaLoadData.trackFormat));
  }

  @Override
  public void onDrmSessionAcquired(EventTime eventTime, @DrmSession.State int state) {
    logd(eventTime, "drmSessionAcquired", "state=" + state);
  }

  @Override
  public void onDrmSessionManagerError(EventTime eventTime, Exception error) {
    printInternalError(eventTime, "drmSessionManagerError", error);
  }

  @Override
  public void onDrmKeysRestored(EventTime eventTime) {
    logd(eventTime, "drmKeysRestored");
  }

  @Override
  public void onDrmKeysRemoved(EventTime eventTime) {
    logd(eventTime, "drmKeysRemoved");
  }

  @Override
  public void onDrmKeysLoaded(EventTime eventTime) {
    logd(eventTime, "drmKeysLoaded");
  }

  @Override
  public void onDrmSessionReleased(EventTime eventTime) {
    logd(eventTime, "drmSessionReleased");
  }

  /**
   * Logs a debug message.
   *
   * @param msg The message to log.
   */
  protected void logd(String msg) {
    Log.d(tag, msg);
  }

  /**
   * Logs an error message.
   *
   * @param msg The message to log.
   */
  protected void loge(String msg) {
    Log.e(tag, msg);
  }

  // Internal methods

  private void logd(EventTime eventTime, String eventName) {
    logd(getEventString(eventTime, eventName, /* eventDescription= */ null, /* throwable= */ null));
  }

  private void logd(EventTime eventTime, String eventName, String eventDescription) {
    logd(getEventString(eventTime, eventName, eventDescription, /* throwable= */ null));
  }

  private void loge(EventTime eventTime, String eventName, @Nullable Throwable throwable) {
    loge(getEventString(eventTime, eventName, /* eventDescription= */ null, throwable));
  }

  private void loge(
      EventTime eventTime,
      String eventName,
      String eventDescription,
      @Nullable Throwable throwable) {
    loge(getEventString(eventTime, eventName, eventDescription, throwable));
  }

  private void printInternalError(EventTime eventTime, String type, Exception e) {
    loge(eventTime, "internalError", type, e);
  }

  private void printMetadata(Metadata metadata, String prefix) {
    for (int i = 0; i < metadata.length(); i++) {
      logd(prefix + metadata.get(i));
    }
  }

  private String getEventString(
      EventTime eventTime,
      String eventName,
      @Nullable String eventDescription,
      @Nullable Throwable throwable) {
    String eventString = eventName + " [" + getEventTimeString(eventTime);
    if (throwable instanceof PlaybackException) {
      eventString += ", errorCode=" + ((PlaybackException) throwable).getErrorCodeName();
    }
    if (eventDescription != null) {
      eventString += ", " + eventDescription;
    }
    @Nullable String throwableString = Log.getThrowableString(throwable);
    if (!TextUtils.isEmpty(throwableString)) {
      eventString += "\n  " + throwableString.replace("\n", "\n  ") + '\n';
    }
    eventString += "]";
    return eventString;
  }

  private String getEventTimeString(EventTime eventTime) {
    String windowPeriodString = "window=" + eventTime.windowIndex;
    if (eventTime.mediaPeriodId != null) {
      windowPeriodString +=
          ", period=" + eventTime.timeline.getIndexOfPeriod(eventTime.mediaPeriodId.periodUid);
      if (eventTime.mediaPeriodId.isAd()) {
        windowPeriodString += ", adGroup=" + eventTime.mediaPeriodId.adGroupIndex;
        windowPeriodString += ", ad=" + eventTime.mediaPeriodId.adIndexInAdGroup;
      }
    }
    return "eventTime="
        + getTimeString(eventTime.realtimeMs - startTimeMs)
        + ", mediaPos="
        + getTimeString(eventTime.eventPlaybackPositionMs)
        + ", "
        + windowPeriodString;
  }

  private static String getTimeString(long timeMs) {
    return timeMs == C.TIME_UNSET ? "?" : TIME_FORMAT.format((timeMs) / 1000f);
  }

  private static String getStateString(int state) {
    switch (state) {
      case Player.STATE_BUFFERING:
        return "BUFFERING";
      case Player.STATE_ENDED:
        return "ENDED";
      case Player.STATE_IDLE:
        return "IDLE";
      case Player.STATE_READY:
        return "READY";
      default:
        return "?";
    }
  }

  private static String getAdaptiveSupportString(
      int trackCount, @AdaptiveSupport int adaptiveSupport) {
    if (trackCount < 2) {
      return "N/A";
    }
    switch (adaptiveSupport) {
      case RendererCapabilities.ADAPTIVE_SEAMLESS:
        return "YES";
      case RendererCapabilities.ADAPTIVE_NOT_SEAMLESS:
        return "YES_NOT_SEAMLESS";
      case RendererCapabilities.ADAPTIVE_NOT_SUPPORTED:
        return "NO";
      default:
        throw new IllegalStateException();
    }
  }

  private static String getTrackStatusString(
      @Nullable TrackSelection selection, TrackGroup group, int trackIndex) {
    return getTrackStatusString(
        selection != null
            && selection.getTrackGroup().equals(group)
            && selection.indexOf(trackIndex) != C.INDEX_UNSET);
  }

  private static String getTrackStatusString(boolean enabled) {
    return enabled ? "[X]" : "[ ]";
  }

  private static String getRepeatModeString(@Player.RepeatMode int repeatMode) {
    switch (repeatMode) {
      case Player.REPEAT_MODE_OFF:
        return "OFF";
      case Player.REPEAT_MODE_ONE:
        return "ONE";
      case Player.REPEAT_MODE_ALL:
        return "ALL";
      default:
        return "?";
    }
  }

  private static String getDiscontinuityReasonString(@Player.DiscontinuityReason int reason) {
    switch (reason) {
      case Player.DISCONTINUITY_REASON_AUTO_TRANSITION:
        return "AUTO_TRANSITION";
      case Player.DISCONTINUITY_REASON_SEEK:
        return "SEEK";
      case Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT:
        return "SEEK_ADJUSTMENT";
      case Player.DISCONTINUITY_REASON_REMOVE:
        return "REMOVE";
      case Player.DISCONTINUITY_REASON_SKIP:
        return "SKIP";
      case Player.DISCONTINUITY_REASON_INTERNAL:
        return "INTERNAL";
      default:
        return "?";
    }
  }

  private static String getTimelineChangeReasonString(@Player.TimelineChangeReason int reason) {
    switch (reason) {
      case Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE:
        return "SOURCE_UPDATE";
      case Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED:
        return "PLAYLIST_CHANGED";
      default:
        return "?";
    }
  }

  private static String getMediaItemTransitionReasonString(
      @Player.MediaItemTransitionReason int reason) {
    switch (reason) {
      case Player.MEDIA_ITEM_TRANSITION_REASON_AUTO:
        return "AUTO";
      case Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED:
        return "PLAYLIST_CHANGED";
      case Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT:
        return "REPEAT";
      case Player.MEDIA_ITEM_TRANSITION_REASON_SEEK:
        return "SEEK";
      default:
        return "?";
    }
  }

  private static String getPlaybackSuppressionReasonString(
      @PlaybackSuppressionReason int playbackSuppressionReason) {
    switch (playbackSuppressionReason) {
      case Player.PLAYBACK_SUPPRESSION_REASON_NONE:
        return "NONE";
      case Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS:
        return "TRANSIENT_AUDIO_FOCUS_LOSS";
      default:
        return "?";
    }
  }

  private static String getPlayWhenReadyChangeReasonString(
      @Player.PlayWhenReadyChangeReason int reason) {
    switch (reason) {
      case Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY:
        return "AUDIO_BECOMING_NOISY";
      case Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS:
        return "AUDIO_FOCUS_LOSS";
      case Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE:
        return "REMOTE";
      case Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST:
        return "USER_REQUEST";
      case Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM:
        return "END_OF_MEDIA_ITEM";
      default:
        return "?";
    }
  }
}
