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
package androidx.media3.exoplayer;

import static androidx.media3.common.Player.COMMAND_CHANGE_MEDIA_ITEMS;
import static androidx.media3.common.Player.COMMAND_GET_CURRENT_MEDIA_ITEM;
import static androidx.media3.common.Player.COMMAND_GET_MEDIA_ITEMS_METADATA;
import static androidx.media3.common.Player.COMMAND_GET_TIMELINE;
import static androidx.media3.common.Player.COMMAND_GET_TRACK_INFOS;
import static androidx.media3.common.Player.COMMAND_PLAY_PAUSE;
import static androidx.media3.common.Player.COMMAND_PREPARE;
import static androidx.media3.common.Player.COMMAND_SEEK_TO_DEFAULT_POSITION;
import static androidx.media3.common.Player.COMMAND_SEEK_TO_MEDIA_ITEM;
import static androidx.media3.common.Player.COMMAND_SET_MEDIA_ITEMS_METADATA;
import static androidx.media3.common.Player.COMMAND_SET_REPEAT_MODE;
import static androidx.media3.common.Player.COMMAND_SET_SHUFFLE_MODE;
import static androidx.media3.common.Player.COMMAND_SET_SPEED_AND_PITCH;
import static androidx.media3.common.Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS;
import static androidx.media3.common.Player.COMMAND_STOP;
import static androidx.media3.common.Player.DISCONTINUITY_REASON_AUTO_TRANSITION;
import static androidx.media3.common.Player.DISCONTINUITY_REASON_INTERNAL;
import static androidx.media3.common.Player.DISCONTINUITY_REASON_REMOVE;
import static androidx.media3.common.Player.DISCONTINUITY_REASON_SEEK;
import static androidx.media3.common.Player.EVENT_MEDIA_METADATA_CHANGED;
import static androidx.media3.common.Player.EVENT_PLAYLIST_METADATA_CHANGED;
import static androidx.media3.common.Player.EVENT_TRACK_SELECTION_PARAMETERS_CHANGED;
import static androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_AUTO;
import static androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED;
import static androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT;
import static androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_SEEK;
import static androidx.media3.common.Player.PLAYBACK_SUPPRESSION_REASON_NONE;
import static androidx.media3.common.Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST;
import static androidx.media3.common.Player.STATE_BUFFERING;
import static androidx.media3.common.Player.STATE_ENDED;
import static androidx.media3.common.Player.STATE_IDLE;
import static androidx.media3.common.Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED;
import static androidx.media3.common.Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE;
import static androidx.media3.common.util.Assertions.checkNotNull;
import static androidx.media3.common.util.Assertions.checkState;
import static androidx.media3.common.util.Util.castNonNull;
import static java.lang.Math.max;
import static java.lang.Math.min;

import android.annotation.SuppressLint;
import android.media.metrics.LogSessionId;
import android.os.Handler;
import android.os.Looper;
import android.util.Pair;
import androidx.annotation.DoNotInline;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.IllegalSeekPositionException;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaLibraryInfo;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Metadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.Player.Commands;
import androidx.media3.common.Player.DiscontinuityReason;
import androidx.media3.common.Player.EventListener;
import androidx.media3.common.Player.Events;
import androidx.media3.common.Player.Listener;
import androidx.media3.common.Player.PlayWhenReadyChangeReason;
import androidx.media3.common.Player.PlaybackSuppressionReason;
import androidx.media3.common.Player.PositionInfo;
import androidx.media3.common.Player.RepeatMode;
import androidx.media3.common.Player.State;
import androidx.media3.common.Player.TimelineChangeReason;
import androidx.media3.common.Timeline;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.TrackGroupArray;
import androidx.media3.common.TrackSelectionArray;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.TracksInfo;
import androidx.media3.common.util.Assertions;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.HandlerWrapper;
import androidx.media3.common.util.ListenerSet;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.ExoPlayer.AudioOffloadListener;
import androidx.media3.exoplayer.PlayerMessage.Target;
import androidx.media3.exoplayer.analytics.AnalyticsCollector;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId;
import androidx.media3.exoplayer.source.MediaSourceFactory;
import androidx.media3.exoplayer.source.ShuffleOrder;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;
import androidx.media3.exoplayer.trackselection.TrackSelector;
import androidx.media3.exoplayer.trackselection.TrackSelectorResult;
import androidx.media3.exoplayer.upstream.BandwidthMeter;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArraySet;

/** A helper class for the {@link SimpleExoPlayer} implementation of {@link ExoPlayer}. */
/* package */ final class ExoPlayerImpl {

  static {
    MediaLibraryInfo.registerModule("media3.exoplayer");
  }

  private static final String TAG = "ExoPlayerImpl";

  /**
   * This empty track selector result can only be used for {@link PlaybackInfo#trackSelectorResult}
   * when the player does not have any track selection made (such as when player is reset, or when
   * player seeks to an unprepared period). It will not be used as result of any {@link
   * TrackSelector#selectTracks(RendererCapabilities[], TrackGroupArray, MediaPeriodId, Timeline)}
   * operation.
   */
  /* package */ final TrackSelectorResult emptyTrackSelectorResult;
  /* package */ final Commands permanentAvailableCommands;

  private final Player wrappingPlayer;
  private final Renderer[] renderers;
  private final TrackSelector trackSelector;
  private final HandlerWrapper playbackInfoUpdateHandler;
  private final ExoPlayerImplInternal.PlaybackInfoUpdateListener playbackInfoUpdateListener;
  private final ExoPlayerImplInternal internalPlayer;
  private final ListenerSet<Player.EventListener> listeners;
  private final CopyOnWriteArraySet<AudioOffloadListener> audioOffloadListeners;
  private final Timeline.Period period;
  private final Timeline.Window window;
  private final List<MediaSourceHolderSnapshot> mediaSourceHolderSnapshots;
  private final boolean useLazyPreparation;
  private final MediaSourceFactory mediaSourceFactory;
  private final AnalyticsCollector analyticsCollector;
  private final Looper applicationLooper;
  private final BandwidthMeter bandwidthMeter;
  private final long seekBackIncrementMs;
  private final long seekForwardIncrementMs;
  private final Clock clock;

  @RepeatMode private int repeatMode;
  private boolean shuffleModeEnabled;
  private int pendingOperationAcks;
  @DiscontinuityReason private int pendingDiscontinuityReason;
  private boolean pendingDiscontinuity;
  @PlayWhenReadyChangeReason private int pendingPlayWhenReadyChangeReason;
  private boolean foregroundMode;
  private SeekParameters seekParameters;
  private ShuffleOrder shuffleOrder;
  private boolean pauseAtEndOfMediaItems;
  private Commands availableCommands;
  private MediaMetadata mediaMetadata;
  private MediaMetadata playlistMetadata;

  // MediaMetadata built from static (TrackGroup Format) and dynamic (onMetadata(Metadata)) metadata
  // sources.
  private MediaMetadata staticAndDynamicMediaMetadata;

  // Playback information when there is no pending seek/set source operation.
  private PlaybackInfo playbackInfo;

  // Playback information when there is a pending seek/set source operation.
  private int maskingWindowIndex;
  private int maskingPeriodIndex;
  private long maskingWindowPositionMs;

  /**
   * Constructs an instance. Must be called from a thread that has an associated {@link Looper}.
   *
   * @param renderers The {@link Renderer}s.
   * @param trackSelector The {@link TrackSelector}.
   * @param mediaSourceFactory The {@link MediaSourceFactory}.
   * @param loadControl The {@link LoadControl}.
   * @param bandwidthMeter The {@link BandwidthMeter}.
   * @param analyticsCollector The {@link AnalyticsCollector}.
   * @param useLazyPreparation Whether playlist items are prepared lazily. If false, all manifest
   *     loads and other initial preparation steps happen immediately. If true, these initial
   *     preparations are triggered only when the player starts buffering the media.
   * @param seekParameters The {@link SeekParameters}.
   * @param seekBackIncrementMs The seek back increment in milliseconds.
   * @param seekForwardIncrementMs The seek forward increment in milliseconds.
   * @param livePlaybackSpeedControl The {@link LivePlaybackSpeedControl}.
   * @param releaseTimeoutMs The timeout for calls to {@link #release()} in milliseconds.
   * @param pauseAtEndOfMediaItems Whether to pause playback at the end of each media item.
   * @param clock The {@link Clock}.
   * @param applicationLooper The {@link Looper} that must be used for all calls to the player and
   *     which is used to call listeners on.
   * @param wrappingPlayer The {@link Player} using this class.
   * @param additionalPermanentAvailableCommands The {@link Commands} that are permanently available
   *     in the wrapping player but that are not in this player.
   */
  @SuppressLint("HandlerLeak")
  public ExoPlayerImpl(
      Renderer[] renderers,
      TrackSelector trackSelector,
      MediaSourceFactory mediaSourceFactory,
      LoadControl loadControl,
      BandwidthMeter bandwidthMeter,
      AnalyticsCollector analyticsCollector,
      boolean useLazyPreparation,
      SeekParameters seekParameters,
      long seekBackIncrementMs,
      long seekForwardIncrementMs,
      LivePlaybackSpeedControl livePlaybackSpeedControl,
      long releaseTimeoutMs,
      boolean pauseAtEndOfMediaItems,
      Clock clock,
      Looper applicationLooper,
      Player wrappingPlayer,
      Commands additionalPermanentAvailableCommands) {
    Log.i(
        TAG,
        "Init "
            + Integer.toHexString(System.identityHashCode(this))
            + " ["
            + MediaLibraryInfo.VERSION_SLASHY
            + "] ["
            + Util.DEVICE_DEBUG_INFO
            + "]");
    checkState(renderers.length > 0);
    this.renderers = checkNotNull(renderers);
    this.trackSelector = checkNotNull(trackSelector);
    this.mediaSourceFactory = mediaSourceFactory;
    this.bandwidthMeter = bandwidthMeter;
    this.analyticsCollector = analyticsCollector;
    this.useLazyPreparation = useLazyPreparation;
    this.seekParameters = seekParameters;
    this.seekBackIncrementMs = seekBackIncrementMs;
    this.seekForwardIncrementMs = seekForwardIncrementMs;
    this.pauseAtEndOfMediaItems = pauseAtEndOfMediaItems;
    this.applicationLooper = applicationLooper;
    this.clock = clock;
    this.wrappingPlayer = wrappingPlayer;
    repeatMode = Player.REPEAT_MODE_OFF;
    listeners =
        new ListenerSet<>(
            applicationLooper,
            clock,
            (listener, flags) -> listener.onEvents(wrappingPlayer, new Events(flags)));
    audioOffloadListeners = new CopyOnWriteArraySet<>();
    mediaSourceHolderSnapshots = new ArrayList<>();
    shuffleOrder = new ShuffleOrder.DefaultShuffleOrder(/* length= */ 0);
    emptyTrackSelectorResult =
        new TrackSelectorResult(
            new RendererConfiguration[renderers.length],
            new ExoTrackSelection[renderers.length],
            TracksInfo.EMPTY,
            /* info= */ null);
    period = new Timeline.Period();
    window = new Timeline.Window();
    permanentAvailableCommands =
        new Commands.Builder()
            .addAll(
                COMMAND_PLAY_PAUSE,
                COMMAND_PREPARE,
                COMMAND_STOP,
                COMMAND_SET_SPEED_AND_PITCH,
                COMMAND_SET_SHUFFLE_MODE,
                COMMAND_SET_REPEAT_MODE,
                COMMAND_GET_CURRENT_MEDIA_ITEM,
                COMMAND_GET_TIMELINE,
                COMMAND_GET_MEDIA_ITEMS_METADATA,
                COMMAND_SET_MEDIA_ITEMS_METADATA,
                COMMAND_CHANGE_MEDIA_ITEMS,
                COMMAND_GET_TRACK_INFOS)
            .addIf(COMMAND_SET_TRACK_SELECTION_PARAMETERS, trackSelector.isSetParametersSupported())
            .addAll(additionalPermanentAvailableCommands)
            .build();
    availableCommands =
        new Commands.Builder()
            .addAll(permanentAvailableCommands)
            .add(COMMAND_SEEK_TO_DEFAULT_POSITION)
            .add(COMMAND_SEEK_TO_MEDIA_ITEM)
            .build();
    mediaMetadata = MediaMetadata.EMPTY;
    playlistMetadata = MediaMetadata.EMPTY;
    staticAndDynamicMediaMetadata = MediaMetadata.EMPTY;
    maskingWindowIndex = C.INDEX_UNSET;
    playbackInfoUpdateHandler = clock.createHandler(applicationLooper, /* callback= */ null);
    playbackInfoUpdateListener =
        playbackInfoUpdate ->
            playbackInfoUpdateHandler.post(() -> handlePlaybackInfo(playbackInfoUpdate));
    playbackInfo = PlaybackInfo.createDummy(emptyTrackSelectorResult);
    analyticsCollector.setPlayer(wrappingPlayer, applicationLooper);
    addListener(analyticsCollector);
    bandwidthMeter.addEventListener(new Handler(applicationLooper), analyticsCollector);
    PlayerId playerId = Util.SDK_INT < 31 ? new PlayerId() : Api31.createPlayerId();
    internalPlayer =
        new ExoPlayerImplInternal(
            renderers,
            trackSelector,
            emptyTrackSelectorResult,
            loadControl,
            bandwidthMeter,
            repeatMode,
            shuffleModeEnabled,
            analyticsCollector,
            seekParameters,
            livePlaybackSpeedControl,
            releaseTimeoutMs,
            pauseAtEndOfMediaItems,
            applicationLooper,
            clock,
            playbackInfoUpdateListener,
            playerId);
  }

  /**
   * Sets a limit on the time a call to {@link #setForegroundMode} can spend. If a call to {@link
   * #setForegroundMode} takes more than {@code timeoutMs} milliseconds to complete, the player will
   * raise an error via {@link Player.Listener#onPlayerError}.
   *
   * <p>This method is experimental, and will be renamed or removed in a future release. It should
   * only be called before the player is used.
   *
   * @param timeoutMs The time limit in milliseconds.
   */
  public void experimentalSetForegroundModeTimeoutMs(long timeoutMs) {
    internalPlayer.experimentalSetForegroundModeTimeoutMs(timeoutMs);
  }

  public void experimentalSetOffloadSchedulingEnabled(boolean offloadSchedulingEnabled) {
    internalPlayer.experimentalSetOffloadSchedulingEnabled(offloadSchedulingEnabled);
  }

  public boolean experimentalIsSleepingForOffload() {
    return playbackInfo.sleepingForOffload;
  }

  public Looper getPlaybackLooper() {
    return internalPlayer.getPlaybackLooper();
  }

  public Looper getApplicationLooper() {
    return applicationLooper;
  }

  public Clock getClock() {
    return clock;
  }

  public void addListener(Listener listener) {
    addEventListener(listener);
  }

  @SuppressWarnings("deprecation") // Register deprecated EventListener.
  public void addEventListener(Player.EventListener eventListener) {
    listeners.add(eventListener);
  }

  @SuppressWarnings("deprecation") // Deregister deprecated EventListener.
  public void removeEventListener(Player.EventListener eventListener) {
    listeners.remove(eventListener);
  }

  public void addAudioOffloadListener(AudioOffloadListener listener) {
    audioOffloadListeners.add(listener);
  }

  public void removeAudioOffloadListener(AudioOffloadListener listener) {
    audioOffloadListeners.remove(listener);
  }

  public Commands getAvailableCommands() {
    return availableCommands;
  }

  @State
  public int getPlaybackState() {
    return playbackInfo.playbackState;
  }

  @PlaybackSuppressionReason
  public int getPlaybackSuppressionReason() {
    return playbackInfo.playbackSuppressionReason;
  }

  @Nullable
  public ExoPlaybackException getPlayerError() {
    return playbackInfo.playbackError;
  }

  /** @deprecated Use {@link #prepare()} instead. */
  @Deprecated
  public void retry() {
    prepare();
  }

  public void prepare() {
    if (playbackInfo.playbackState != Player.STATE_IDLE) {
      return;
    }
    PlaybackInfo playbackInfo = this.playbackInfo.copyWithPlaybackError(null);
    playbackInfo =
        playbackInfo.copyWithPlaybackState(
            playbackInfo.timeline.isEmpty() ? STATE_ENDED : STATE_BUFFERING);
    // Trigger internal prepare first before updating the playback info and notifying external
    // listeners to ensure that new operations issued in the listener notifications reach the
    // player after this prepare. The internal player can't change the playback info immediately
    // because it uses a callback.
    pendingOperationAcks++;
    internalPlayer.prepare();
    updatePlaybackInfo(
        playbackInfo,
        /* ignored */ TIMELINE_CHANGE_REASON_SOURCE_UPDATE,
        /* ignored */ PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
        /* seekProcessed= */ false,
        /* positionDiscontinuity= */ false,
        /* ignored */ DISCONTINUITY_REASON_INTERNAL,
        /* ignored */ C.TIME_UNSET,
        /* ignored */ C.INDEX_UNSET);
  }

  /**
   * @deprecated Use {@link #setMediaSource(MediaSource)} and {@link ExoPlayer#prepare()} instead.
   */
  @Deprecated
  public void prepare(MediaSource mediaSource) {
    setMediaSource(mediaSource);
    prepare();
  }

  /**
   * @deprecated Use {@link #setMediaSource(MediaSource, boolean)} and {@link ExoPlayer#prepare()}
   *     instead.
   */
  @Deprecated
  public void prepare(MediaSource mediaSource, boolean resetPosition, boolean resetState) {
    setMediaSource(mediaSource, resetPosition);
    prepare();
  }

  public void setMediaItems(List<MediaItem> mediaItems, boolean resetPosition) {
    setMediaSources(createMediaSources(mediaItems), resetPosition);
  }

  public void setMediaItems(List<MediaItem> mediaItems, int startIndex, long startPositionMs) {
    setMediaSources(createMediaSources(mediaItems), startIndex, startPositionMs);
  }

  public void setMediaSource(MediaSource mediaSource) {
    setMediaSources(Collections.singletonList(mediaSource));
  }

  public void setMediaSource(MediaSource mediaSource, long startPositionMs) {
    setMediaSources(
        Collections.singletonList(mediaSource), /* startWindowIndex= */ 0, startPositionMs);
  }

  public void setMediaSource(MediaSource mediaSource, boolean resetPosition) {
    setMediaSources(Collections.singletonList(mediaSource), resetPosition);
  }

  public void setMediaSources(List<MediaSource> mediaSources) {
    setMediaSources(mediaSources, /* resetPosition= */ true);
  }

  public void setMediaSources(List<MediaSource> mediaSources, boolean resetPosition) {
    setMediaSourcesInternal(
        mediaSources,
        /* startWindowIndex= */ C.INDEX_UNSET,
        /* startPositionMs= */ C.TIME_UNSET,
        /* resetToDefaultPosition= */ resetPosition);
  }

  public void setMediaSources(
      List<MediaSource> mediaSources, int startWindowIndex, long startPositionMs) {
    setMediaSourcesInternal(
        mediaSources, startWindowIndex, startPositionMs, /* resetToDefaultPosition= */ false);
  }

  public void addMediaItems(int index, List<MediaItem> mediaItems) {
    index = min(index, mediaSourceHolderSnapshots.size());
    addMediaSources(index, createMediaSources(mediaItems));
  }

  public void addMediaSource(MediaSource mediaSource) {
    addMediaSources(Collections.singletonList(mediaSource));
  }

  public void addMediaSource(int index, MediaSource mediaSource) {
    addMediaSources(index, Collections.singletonList(mediaSource));
  }

  public void addMediaSources(List<MediaSource> mediaSources) {
    addMediaSources(/* index= */ mediaSourceHolderSnapshots.size(), mediaSources);
  }

  public void addMediaSources(int index, List<MediaSource> mediaSources) {
    Assertions.checkArgument(index >= 0);
    Timeline oldTimeline = getCurrentTimeline();
    pendingOperationAcks++;
    List<MediaSourceList.MediaSourceHolder> holders = addMediaSourceHolders(index, mediaSources);
    Timeline newTimeline = createMaskingTimeline();
    PlaybackInfo newPlaybackInfo =
        maskTimelineAndPosition(
            playbackInfo,
            newTimeline,
            getPeriodPositionUsAfterTimelineChanged(oldTimeline, newTimeline));
    internalPlayer.addMediaSources(index, holders, shuffleOrder);
    updatePlaybackInfo(
        newPlaybackInfo,
        /* timelineChangeReason= */ TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED,
        /* ignored */ PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
        /* seekProcessed= */ false,
        /* positionDiscontinuity= */ false,
        /* ignored */ DISCONTINUITY_REASON_INTERNAL,
        /* ignored */ C.TIME_UNSET,
        /* ignored */ C.INDEX_UNSET);
  }

  public void removeMediaItems(int fromIndex, int toIndex) {
    toIndex = min(toIndex, mediaSourceHolderSnapshots.size());
    PlaybackInfo newPlaybackInfo = removeMediaItemsInternal(fromIndex, toIndex);
    boolean positionDiscontinuity =
        !newPlaybackInfo.periodId.periodUid.equals(playbackInfo.periodId.periodUid);
    updatePlaybackInfo(
        newPlaybackInfo,
        /* timelineChangeReason= */ TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED,
        /* ignored */ PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
        /* seekProcessed= */ false,
        positionDiscontinuity,
        DISCONTINUITY_REASON_REMOVE,
        /* discontinuityWindowStartPositionUs= */ getCurrentPositionUsInternal(newPlaybackInfo),
        /* ignored */ C.INDEX_UNSET);
  }

  public void moveMediaItems(int fromIndex, int toIndex, int newFromIndex) {
    Assertions.checkArgument(
        fromIndex >= 0
            && fromIndex <= toIndex
            && toIndex <= mediaSourceHolderSnapshots.size()
            && newFromIndex >= 0);
    Timeline oldTimeline = getCurrentTimeline();
    pendingOperationAcks++;
    newFromIndex = min(newFromIndex, mediaSourceHolderSnapshots.size() - (toIndex - fromIndex));
    Util.moveItems(mediaSourceHolderSnapshots, fromIndex, toIndex, newFromIndex);
    Timeline newTimeline = createMaskingTimeline();
    PlaybackInfo newPlaybackInfo =
        maskTimelineAndPosition(
            playbackInfo,
            newTimeline,
            getPeriodPositionUsAfterTimelineChanged(oldTimeline, newTimeline));
    internalPlayer.moveMediaSources(fromIndex, toIndex, newFromIndex, shuffleOrder);
    updatePlaybackInfo(
        newPlaybackInfo,
        /* timelineChangeReason= */ TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED,
        /* ignored */ PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
        /* seekProcessed= */ false,
        /* positionDiscontinuity= */ false,
        /* ignored */ DISCONTINUITY_REASON_INTERNAL,
        /* ignored */ C.TIME_UNSET,
        /* ignored */ C.INDEX_UNSET);
  }

  public void setShuffleOrder(ShuffleOrder shuffleOrder) {
    Timeline timeline = createMaskingTimeline();
    PlaybackInfo newPlaybackInfo =
        maskTimelineAndPosition(
            playbackInfo,
            timeline,
            maskWindowPositionMsOrGetPeriodPositionUs(
                timeline, getCurrentMediaItemIndex(), getCurrentPosition()));
    pendingOperationAcks++;
    this.shuffleOrder = shuffleOrder;
    internalPlayer.setShuffleOrder(shuffleOrder);
    updatePlaybackInfo(
        newPlaybackInfo,
        /* timelineChangeReason= */ TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED,
        /* ignored */ PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
        /* seekProcessed= */ false,
        /* positionDiscontinuity= */ false,
        /* ignored */ DISCONTINUITY_REASON_INTERNAL,
        /* ignored */ C.TIME_UNSET,
        /* ignored */ C.INDEX_UNSET);
  }

  public void setPauseAtEndOfMediaItems(boolean pauseAtEndOfMediaItems) {
    if (this.pauseAtEndOfMediaItems == pauseAtEndOfMediaItems) {
      return;
    }
    this.pauseAtEndOfMediaItems = pauseAtEndOfMediaItems;
    internalPlayer.setPauseAtEndOfWindow(pauseAtEndOfMediaItems);
  }

  public boolean getPauseAtEndOfMediaItems() {
    return pauseAtEndOfMediaItems;
  }

  public void setPlayWhenReady(
      boolean playWhenReady,
      @PlaybackSuppressionReason int playbackSuppressionReason,
      @PlayWhenReadyChangeReason int playWhenReadyChangeReason) {
    if (playbackInfo.playWhenReady == playWhenReady
        && playbackInfo.playbackSuppressionReason == playbackSuppressionReason) {
      return;
    }
    pendingOperationAcks++;
    PlaybackInfo playbackInfo =
        this.playbackInfo.copyWithPlayWhenReady(playWhenReady, playbackSuppressionReason);
    internalPlayer.setPlayWhenReady(playWhenReady, playbackSuppressionReason);
    updatePlaybackInfo(
        playbackInfo,
        /* ignored */ TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED,
        playWhenReadyChangeReason,
        /* seekProcessed= */ false,
        /* positionDiscontinuity= */ false,
        /* ignored */ DISCONTINUITY_REASON_INTERNAL,
        /* ignored */ C.TIME_UNSET,
        /* ignored */ C.INDEX_UNSET);
  }

  public boolean getPlayWhenReady() {
    return playbackInfo.playWhenReady;
  }

  public void setRepeatMode(@RepeatMode int repeatMode) {
    if (this.repeatMode != repeatMode) {
      this.repeatMode = repeatMode;
      internalPlayer.setRepeatMode(repeatMode);
      listeners.queueEvent(
          Player.EVENT_REPEAT_MODE_CHANGED, listener -> listener.onRepeatModeChanged(repeatMode));
      updateAvailableCommands();
      listeners.flushEvents();
    }
  }

  @RepeatMode
  public int getRepeatMode() {
    return repeatMode;
  }

  public void setShuffleModeEnabled(boolean shuffleModeEnabled) {
    if (this.shuffleModeEnabled != shuffleModeEnabled) {
      this.shuffleModeEnabled = shuffleModeEnabled;
      internalPlayer.setShuffleModeEnabled(shuffleModeEnabled);
      listeners.queueEvent(
          Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
          listener -> listener.onShuffleModeEnabledChanged(shuffleModeEnabled));
      updateAvailableCommands();
      listeners.flushEvents();
    }
  }

  public boolean getShuffleModeEnabled() {
    return shuffleModeEnabled;
  }

  public boolean isLoading() {
    return playbackInfo.isLoading;
  }

  public void seekTo(int mediaItemIndex, long positionMs) {
    Timeline timeline = playbackInfo.timeline;
    if (mediaItemIndex < 0
        || (!timeline.isEmpty() && mediaItemIndex >= timeline.getWindowCount())) {
      throw new IllegalSeekPositionException(timeline, mediaItemIndex, positionMs);
    }
    pendingOperationAcks++;
    if (isPlayingAd()) {
      // TODO: Investigate adding support for seeking during ads. This is complicated to do in
      // general because the midroll ad preceding the seek destination must be played before the
      // content position can be played, if a different ad is playing at the moment.
      Log.w(TAG, "seekTo ignored because an ad is playing");
      ExoPlayerImplInternal.PlaybackInfoUpdate playbackInfoUpdate =
          new ExoPlayerImplInternal.PlaybackInfoUpdate(this.playbackInfo);
      playbackInfoUpdate.incrementPendingOperationAcks(1);
      playbackInfoUpdateListener.onPlaybackInfoUpdate(playbackInfoUpdate);
      return;
    }
    @Player.State
    int newPlaybackState =
        getPlaybackState() == Player.STATE_IDLE ? Player.STATE_IDLE : STATE_BUFFERING;
    int oldMaskingMediaItemIndex = getCurrentMediaItemIndex();
    PlaybackInfo newPlaybackInfo = playbackInfo.copyWithPlaybackState(newPlaybackState);
    newPlaybackInfo =
        maskTimelineAndPosition(
            newPlaybackInfo,
            timeline,
            maskWindowPositionMsOrGetPeriodPositionUs(timeline, mediaItemIndex, positionMs));
    internalPlayer.seekTo(timeline, mediaItemIndex, Util.msToUs(positionMs));
    updatePlaybackInfo(
        newPlaybackInfo,
        /* ignored */ TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED,
        /* ignored */ PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
        /* seekProcessed= */ true,
        /* positionDiscontinuity= */ true,
        /* positionDiscontinuityReason= */ DISCONTINUITY_REASON_SEEK,
        /* discontinuityWindowStartPositionUs= */ getCurrentPositionUsInternal(newPlaybackInfo),
        oldMaskingMediaItemIndex);
  }

  public long getSeekBackIncrement() {
    return seekBackIncrementMs;
  }

  public long getSeekForwardIncrement() {
    return seekForwardIncrementMs;
  }

  public long getMaxSeekToPreviousPosition() {
    return C.DEFAULT_MAX_SEEK_TO_PREVIOUS_POSITION_MS;
  }

  public void setPlaybackParameters(PlaybackParameters playbackParameters) {
    if (playbackParameters == null) {
      playbackParameters = PlaybackParameters.DEFAULT;
    }
    if (playbackInfo.playbackParameters.equals(playbackParameters)) {
      return;
    }
    PlaybackInfo newPlaybackInfo = playbackInfo.copyWithPlaybackParameters(playbackParameters);
    pendingOperationAcks++;
    internalPlayer.setPlaybackParameters(playbackParameters);
    updatePlaybackInfo(
        newPlaybackInfo,
        /* ignored */ TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED,
        /* ignored */ PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
        /* seekProcessed= */ false,
        /* positionDiscontinuity= */ false,
        /* ignored */ DISCONTINUITY_REASON_INTERNAL,
        /* ignored */ C.TIME_UNSET,
        /* ignored */ C.INDEX_UNSET);
  }

  public PlaybackParameters getPlaybackParameters() {
    return playbackInfo.playbackParameters;
  }

  public void setSeekParameters(@Nullable SeekParameters seekParameters) {
    if (seekParameters == null) {
      seekParameters = SeekParameters.DEFAULT;
    }
    if (!this.seekParameters.equals(seekParameters)) {
      this.seekParameters = seekParameters;
      internalPlayer.setSeekParameters(seekParameters);
    }
  }

  public SeekParameters getSeekParameters() {
    return seekParameters;
  }

  public void setForegroundMode(boolean foregroundMode) {
    if (this.foregroundMode != foregroundMode) {
      this.foregroundMode = foregroundMode;
      if (!internalPlayer.setForegroundMode(foregroundMode)) {
        // One of the renderers timed out releasing its resources.
        stop(
            /* reset= */ false,
            ExoPlaybackException.createForUnexpected(
                new ExoTimeoutException(ExoTimeoutException.TIMEOUT_OPERATION_SET_FOREGROUND_MODE),
                PlaybackException.ERROR_CODE_TIMEOUT));
      }
    }
  }

  public void stop(boolean reset) {
    stop(reset, /* error= */ null);
  }

  /**
   * Stops the player.
   *
   * @param reset Whether the playlist should be cleared and whether the playback position and
   *     playback error should be reset.
   * @param error An optional {@link ExoPlaybackException} to set.
   */
  public void stop(boolean reset, @Nullable ExoPlaybackException error) {
    PlaybackInfo playbackInfo;
    if (reset) {
      playbackInfo =
          removeMediaItemsInternal(
              /* fromIndex= */ 0, /* toIndex= */ mediaSourceHolderSnapshots.size());
      playbackInfo = playbackInfo.copyWithPlaybackError(null);
    } else {
      playbackInfo = this.playbackInfo.copyWithLoadingMediaPeriodId(this.playbackInfo.periodId);
      playbackInfo.bufferedPositionUs = playbackInfo.positionUs;
      playbackInfo.totalBufferedDurationUs = 0;
    }
    playbackInfo = playbackInfo.copyWithPlaybackState(Player.STATE_IDLE);
    if (error != null) {
      playbackInfo = playbackInfo.copyWithPlaybackError(error);
    }
    pendingOperationAcks++;
    internalPlayer.stop();
    boolean positionDiscontinuity =
        playbackInfo.timeline.isEmpty() && !this.playbackInfo.timeline.isEmpty();
    updatePlaybackInfo(
        playbackInfo,
        TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED,
        /* ignored */ PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
        /* seekProcessed= */ false,
        positionDiscontinuity,
        DISCONTINUITY_REASON_REMOVE,
        /* discontinuityWindowStartPositionUs= */ getCurrentPositionUsInternal(playbackInfo),
        /* ignored */ C.INDEX_UNSET);
  }

  public void release() {
    Log.i(
        TAG,
        "Release "
            + Integer.toHexString(System.identityHashCode(this))
            + " ["
            + MediaLibraryInfo.VERSION_SLASHY
            + "] ["
            + Util.DEVICE_DEBUG_INFO
            + "] ["
            + MediaLibraryInfo.registeredModules()
            + "]");
    if (!internalPlayer.release()) {
      // One of the renderers timed out releasing its resources.
      listeners.sendEvent(
          Player.EVENT_PLAYER_ERROR,
          listener ->
              listener.onPlayerError(
                  ExoPlaybackException.createForUnexpected(
                      new ExoTimeoutException(ExoTimeoutException.TIMEOUT_OPERATION_RELEASE),
                      PlaybackException.ERROR_CODE_TIMEOUT)));
    }
    listeners.release();
    playbackInfoUpdateHandler.removeCallbacksAndMessages(null);
    bandwidthMeter.removeEventListener(analyticsCollector);
    playbackInfo = playbackInfo.copyWithPlaybackState(Player.STATE_IDLE);
    playbackInfo = playbackInfo.copyWithLoadingMediaPeriodId(playbackInfo.periodId);
    playbackInfo.bufferedPositionUs = playbackInfo.positionUs;
    playbackInfo.totalBufferedDurationUs = 0;
  }

  public PlayerMessage createMessage(Target target) {
    return new PlayerMessage(
        internalPlayer,
        target,
        playbackInfo.timeline,
        getCurrentMediaItemIndex(),
        clock,
        internalPlayer.getPlaybackLooper());
  }

  public int getCurrentPeriodIndex() {
    if (playbackInfo.timeline.isEmpty()) {
      return maskingPeriodIndex;
    } else {
      return playbackInfo.timeline.getIndexOfPeriod(playbackInfo.periodId.periodUid);
    }
  }

  public int getCurrentMediaItemIndex() {
    int currentWindowIndex = getCurrentWindowIndexInternal();
    return currentWindowIndex == C.INDEX_UNSET ? 0 : currentWindowIndex;
  }

  public long getDuration() {
    if (isPlayingAd()) {
      MediaPeriodId periodId = playbackInfo.periodId;
      playbackInfo.timeline.getPeriodByUid(periodId.periodUid, period);
      long adDurationUs = period.getAdDurationUs(periodId.adGroupIndex, periodId.adIndexInAdGroup);
      return Util.usToMs(adDurationUs);
    }
    return getContentDuration();
  }

  private long getContentDuration() {
    Timeline timeline = getCurrentTimeline();
    return timeline.isEmpty()
        ? C.TIME_UNSET
        : timeline.getWindow(getCurrentMediaItemIndex(), window).getDurationMs();
  }

  public long getCurrentPosition() {
    return Util.usToMs(getCurrentPositionUsInternal(playbackInfo));
  }

  public long getBufferedPosition() {
    if (isPlayingAd()) {
      return playbackInfo.loadingMediaPeriodId.equals(playbackInfo.periodId)
          ? Util.usToMs(playbackInfo.bufferedPositionUs)
          : getDuration();
    }
    return getContentBufferedPosition();
  }

  public long getTotalBufferedDuration() {
    return Util.usToMs(playbackInfo.totalBufferedDurationUs);
  }

  public boolean isPlayingAd() {
    return playbackInfo.periodId.isAd();
  }

  public int getCurrentAdGroupIndex() {
    return isPlayingAd() ? playbackInfo.periodId.adGroupIndex : C.INDEX_UNSET;
  }

  public int getCurrentAdIndexInAdGroup() {
    return isPlayingAd() ? playbackInfo.periodId.adIndexInAdGroup : C.INDEX_UNSET;
  }

  public long getContentPosition() {
    if (isPlayingAd()) {
      playbackInfo.timeline.getPeriodByUid(playbackInfo.periodId.periodUid, period);
      return playbackInfo.requestedContentPositionUs == C.TIME_UNSET
          ? playbackInfo
              .timeline
              .getWindow(getCurrentMediaItemIndex(), window)
              .getDefaultPositionMs()
          : period.getPositionInWindowMs() + Util.usToMs(playbackInfo.requestedContentPositionUs);
    } else {
      return getCurrentPosition();
    }
  }

  public long getContentBufferedPosition() {
    if (playbackInfo.timeline.isEmpty()) {
      return maskingWindowPositionMs;
    }
    if (playbackInfo.loadingMediaPeriodId.windowSequenceNumber
        != playbackInfo.periodId.windowSequenceNumber) {
      return playbackInfo.timeline.getWindow(getCurrentMediaItemIndex(), window).getDurationMs();
    }
    long contentBufferedPositionUs = playbackInfo.bufferedPositionUs;
    if (playbackInfo.loadingMediaPeriodId.isAd()) {
      Timeline.Period loadingPeriod =
          playbackInfo.timeline.getPeriodByUid(playbackInfo.loadingMediaPeriodId.periodUid, period);
      contentBufferedPositionUs =
          loadingPeriod.getAdGroupTimeUs(playbackInfo.loadingMediaPeriodId.adGroupIndex);
      if (contentBufferedPositionUs == C.TIME_END_OF_SOURCE) {
        contentBufferedPositionUs = loadingPeriod.durationUs;
      }
    }
    return Util.usToMs(
        periodPositionUsToWindowPositionUs(
            playbackInfo.timeline, playbackInfo.loadingMediaPeriodId, contentBufferedPositionUs));
  }

  public int getRendererCount() {
    return renderers.length;
  }

  public @C.TrackType int getRendererType(int index) {
    return renderers[index].getTrackType();
  }

  public TrackSelector getTrackSelector() {
    return trackSelector;
  }

  public TrackGroupArray getCurrentTrackGroups() {
    return playbackInfo.trackGroups;
  }

  public TrackSelectionArray getCurrentTrackSelections() {
    return new TrackSelectionArray(playbackInfo.trackSelectorResult.selections);
  }

  public TracksInfo getCurrentTracksInfo() {
    return playbackInfo.trackSelectorResult.tracksInfo;
  }

  public TrackSelectionParameters getTrackSelectionParameters() {
    return trackSelector.getParameters();
  }

  public void setTrackSelectionParameters(TrackSelectionParameters parameters) {
    if (!trackSelector.isSetParametersSupported()
        || parameters.equals(trackSelector.getParameters())) {
      return;
    }
    trackSelector.setParameters(parameters);
    listeners.queueEvent(
        EVENT_TRACK_SELECTION_PARAMETERS_CHANGED,
        listener -> listener.onTrackSelectionParametersChanged(parameters));
  }

  public MediaMetadata getMediaMetadata() {
    return mediaMetadata;
  }

  public void onMetadata(Metadata metadata) {
    staticAndDynamicMediaMetadata =
        staticAndDynamicMediaMetadata.buildUpon().populateFromMetadata(metadata).build();

    MediaMetadata newMediaMetadata = buildUpdatedMediaMetadata();

    if (newMediaMetadata.equals(mediaMetadata)) {
      return;
    }
    mediaMetadata = newMediaMetadata;
    listeners.sendEvent(
        EVENT_MEDIA_METADATA_CHANGED, listener -> listener.onMediaMetadataChanged(mediaMetadata));
  }

  public MediaMetadata getPlaylistMetadata() {
    return playlistMetadata;
  }

  public void setPlaylistMetadata(MediaMetadata playlistMetadata) {
    checkNotNull(playlistMetadata);
    if (playlistMetadata.equals(this.playlistMetadata)) {
      return;
    }
    this.playlistMetadata = playlistMetadata;
    listeners.sendEvent(
        EVENT_PLAYLIST_METADATA_CHANGED,
        listener -> listener.onPlaylistMetadataChanged(this.playlistMetadata));
  }

  public Timeline getCurrentTimeline() {
    return playbackInfo.timeline;
  }

  private int getCurrentWindowIndexInternal() {
    if (playbackInfo.timeline.isEmpty()) {
      return maskingWindowIndex;
    } else {
      return playbackInfo.timeline.getPeriodByUid(playbackInfo.periodId.periodUid, period)
          .windowIndex;
    }
  }

  private long getCurrentPositionUsInternal(PlaybackInfo playbackInfo) {
    if (playbackInfo.timeline.isEmpty()) {
      return Util.msToUs(maskingWindowPositionMs);
    } else if (playbackInfo.periodId.isAd()) {
      return playbackInfo.positionUs;
    } else {
      return periodPositionUsToWindowPositionUs(
          playbackInfo.timeline, playbackInfo.periodId, playbackInfo.positionUs);
    }
  }

  private List<MediaSource> createMediaSources(List<MediaItem> mediaItems) {
    List<MediaSource> mediaSources = new ArrayList<>();
    for (int i = 0; i < mediaItems.size(); i++) {
      mediaSources.add(mediaSourceFactory.createMediaSource(mediaItems.get(i)));
    }
    return mediaSources;
  }

  private void handlePlaybackInfo(ExoPlayerImplInternal.PlaybackInfoUpdate playbackInfoUpdate) {
    pendingOperationAcks -= playbackInfoUpdate.operationAcks;
    if (playbackInfoUpdate.positionDiscontinuity) {
      pendingDiscontinuityReason = playbackInfoUpdate.discontinuityReason;
      pendingDiscontinuity = true;
    }
    if (playbackInfoUpdate.hasPlayWhenReadyChangeReason) {
      pendingPlayWhenReadyChangeReason = playbackInfoUpdate.playWhenReadyChangeReason;
    }
    if (pendingOperationAcks == 0) {
      Timeline newTimeline = playbackInfoUpdate.playbackInfo.timeline;
      if (!this.playbackInfo.timeline.isEmpty() && newTimeline.isEmpty()) {
        // Update the masking variables, which are used when the timeline becomes empty because a
        // ConcatenatingMediaSource has been cleared.
        maskingWindowIndex = C.INDEX_UNSET;
        maskingWindowPositionMs = 0;
        maskingPeriodIndex = 0;
      }
      if (!newTimeline.isEmpty()) {
        List<Timeline> timelines = ((PlaylistTimeline) newTimeline).getChildTimelines();
        checkState(timelines.size() == mediaSourceHolderSnapshots.size());
        for (int i = 0; i < timelines.size(); i++) {
          mediaSourceHolderSnapshots.get(i).timeline = timelines.get(i);
        }
      }
      boolean positionDiscontinuity = false;
      long discontinuityWindowStartPositionUs = C.TIME_UNSET;
      if (pendingDiscontinuity) {
        positionDiscontinuity =
            !playbackInfoUpdate.playbackInfo.periodId.equals(playbackInfo.periodId)
                || playbackInfoUpdate.playbackInfo.discontinuityStartPositionUs
                    != playbackInfo.positionUs;
        if (positionDiscontinuity) {
          discontinuityWindowStartPositionUs =
              newTimeline.isEmpty() || playbackInfoUpdate.playbackInfo.periodId.isAd()
                  ? playbackInfoUpdate.playbackInfo.discontinuityStartPositionUs
                  : periodPositionUsToWindowPositionUs(
                      newTimeline,
                      playbackInfoUpdate.playbackInfo.periodId,
                      playbackInfoUpdate.playbackInfo.discontinuityStartPositionUs);
        }
      }
      pendingDiscontinuity = false;
      updatePlaybackInfo(
          playbackInfoUpdate.playbackInfo,
          TIMELINE_CHANGE_REASON_SOURCE_UPDATE,
          pendingPlayWhenReadyChangeReason,
          /* seekProcessed= */ false,
          positionDiscontinuity,
          pendingDiscontinuityReason,
          discontinuityWindowStartPositionUs,
          /* ignored */ C.INDEX_UNSET);
    }
  }

  // Calling deprecated listeners.
  @SuppressWarnings("deprecation")
  private void updatePlaybackInfo(
      PlaybackInfo playbackInfo,
      @TimelineChangeReason int timelineChangeReason,
      @PlayWhenReadyChangeReason int playWhenReadyChangeReason,
      boolean seekProcessed,
      boolean positionDiscontinuity,
      @DiscontinuityReason int positionDiscontinuityReason,
      long discontinuityWindowStartPositionUs,
      int oldMaskingMediaItemIndex) {

    // Assign playback info immediately such that all getters return the right values, but keep
    // snapshot of previous and new state so that listener invocations are triggered correctly.
    PlaybackInfo previousPlaybackInfo = this.playbackInfo;
    PlaybackInfo newPlaybackInfo = playbackInfo;
    this.playbackInfo = playbackInfo;

    Pair<Boolean, Integer> mediaItemTransitionInfo =
        evaluateMediaItemTransitionReason(
            newPlaybackInfo,
            previousPlaybackInfo,
            positionDiscontinuity,
            positionDiscontinuityReason,
            !previousPlaybackInfo.timeline.equals(newPlaybackInfo.timeline));
    boolean mediaItemTransitioned = mediaItemTransitionInfo.first;
    int mediaItemTransitionReason = mediaItemTransitionInfo.second;
    MediaMetadata newMediaMetadata = mediaMetadata;
    @Nullable MediaItem mediaItem = null;
    if (mediaItemTransitioned) {
      if (!newPlaybackInfo.timeline.isEmpty()) {
        int windowIndex =
            newPlaybackInfo.timeline.getPeriodByUid(newPlaybackInfo.periodId.periodUid, period)
                .windowIndex;
        mediaItem = newPlaybackInfo.timeline.getWindow(windowIndex, window).mediaItem;
      }
      staticAndDynamicMediaMetadata = MediaMetadata.EMPTY;
    }
    if (mediaItemTransitioned
        || !previousPlaybackInfo.staticMetadata.equals(newPlaybackInfo.staticMetadata)) {
      staticAndDynamicMediaMetadata =
          staticAndDynamicMediaMetadata
              .buildUpon()
              .populateFromMetadata(newPlaybackInfo.staticMetadata)
              .build();

      newMediaMetadata = buildUpdatedMediaMetadata();
    }
    boolean metadataChanged = !newMediaMetadata.equals(mediaMetadata);
    mediaMetadata = newMediaMetadata;

    if (!previousPlaybackInfo.timeline.equals(newPlaybackInfo.timeline)) {
      listeners.queueEvent(
          Player.EVENT_TIMELINE_CHANGED,
          listener -> listener.onTimelineChanged(newPlaybackInfo.timeline, timelineChangeReason));
    }
    if (positionDiscontinuity) {
      PositionInfo previousPositionInfo =
          getPreviousPositionInfo(
              positionDiscontinuityReason, previousPlaybackInfo, oldMaskingMediaItemIndex);
      PositionInfo positionInfo = getPositionInfo(discontinuityWindowStartPositionUs);
      listeners.queueEvent(
          Player.EVENT_POSITION_DISCONTINUITY,
          listener -> {
            listener.onPositionDiscontinuity(positionDiscontinuityReason);
            listener.onPositionDiscontinuity(
                previousPositionInfo, positionInfo, positionDiscontinuityReason);
          });
    }
    if (mediaItemTransitioned) {
      @Nullable final MediaItem finalMediaItem = mediaItem;
      listeners.queueEvent(
          Player.EVENT_MEDIA_ITEM_TRANSITION,
          listener -> listener.onMediaItemTransition(finalMediaItem, mediaItemTransitionReason));
    }
    if (previousPlaybackInfo.playbackError != newPlaybackInfo.playbackError) {
      listeners.queueEvent(
          Player.EVENT_PLAYER_ERROR,
          listener -> listener.onPlayerErrorChanged(newPlaybackInfo.playbackError));
      if (newPlaybackInfo.playbackError != null) {
        listeners.queueEvent(
            Player.EVENT_PLAYER_ERROR,
            listener -> listener.onPlayerError(newPlaybackInfo.playbackError));
      }
    }
    if (previousPlaybackInfo.trackSelectorResult != newPlaybackInfo.trackSelectorResult) {
      trackSelector.onSelectionActivated(newPlaybackInfo.trackSelectorResult.info);
      TrackSelectionArray newSelection =
          new TrackSelectionArray(newPlaybackInfo.trackSelectorResult.selections);
      listeners.queueEvent(
          Player.EVENT_TRACKS_CHANGED,
          listener -> listener.onTracksChanged(newPlaybackInfo.trackGroups, newSelection));
      listeners.queueEvent(
          Player.EVENT_TRACKS_CHANGED,
          listener -> listener.onTracksInfoChanged(newPlaybackInfo.trackSelectorResult.tracksInfo));
    }
    if (metadataChanged) {
      final MediaMetadata finalMediaMetadata = mediaMetadata;
      listeners.queueEvent(
          EVENT_MEDIA_METADATA_CHANGED,
          listener -> listener.onMediaMetadataChanged(finalMediaMetadata));
    }
    if (previousPlaybackInfo.isLoading != newPlaybackInfo.isLoading) {
      listeners.queueEvent(
          Player.EVENT_IS_LOADING_CHANGED,
          listener -> {
            listener.onLoadingChanged(newPlaybackInfo.isLoading);
            listener.onIsLoadingChanged(newPlaybackInfo.isLoading);
          });
    }
    if (previousPlaybackInfo.playbackState != newPlaybackInfo.playbackState
        || previousPlaybackInfo.playWhenReady != newPlaybackInfo.playWhenReady) {
      listeners.queueEvent(
          /* eventFlag= */ C.INDEX_UNSET,
          listener ->
              listener.onPlayerStateChanged(
                  newPlaybackInfo.playWhenReady, newPlaybackInfo.playbackState));
    }
    if (previousPlaybackInfo.playbackState != newPlaybackInfo.playbackState) {
      listeners.queueEvent(
          Player.EVENT_PLAYBACK_STATE_CHANGED,
          listener -> listener.onPlaybackStateChanged(newPlaybackInfo.playbackState));
    }
    if (previousPlaybackInfo.playWhenReady != newPlaybackInfo.playWhenReady) {
      listeners.queueEvent(
          Player.EVENT_PLAY_WHEN_READY_CHANGED,
          listener ->
              listener.onPlayWhenReadyChanged(
                  newPlaybackInfo.playWhenReady, playWhenReadyChangeReason));
    }
    if (previousPlaybackInfo.playbackSuppressionReason
        != newPlaybackInfo.playbackSuppressionReason) {
      listeners.queueEvent(
          Player.EVENT_PLAYBACK_SUPPRESSION_REASON_CHANGED,
          listener ->
              listener.onPlaybackSuppressionReasonChanged(
                  newPlaybackInfo.playbackSuppressionReason));
    }
    if (isPlaying(previousPlaybackInfo) != isPlaying(newPlaybackInfo)) {
      listeners.queueEvent(
          Player.EVENT_IS_PLAYING_CHANGED,
          listener -> listener.onIsPlayingChanged(isPlaying(newPlaybackInfo)));
    }
    if (!previousPlaybackInfo.playbackParameters.equals(newPlaybackInfo.playbackParameters)) {
      listeners.queueEvent(
          Player.EVENT_PLAYBACK_PARAMETERS_CHANGED,
          listener -> listener.onPlaybackParametersChanged(newPlaybackInfo.playbackParameters));
    }
    if (seekProcessed) {
      listeners.queueEvent(/* eventFlag= */ C.INDEX_UNSET, EventListener::onSeekProcessed);
    }
    updateAvailableCommands();
    listeners.flushEvents();

    if (previousPlaybackInfo.offloadSchedulingEnabled != newPlaybackInfo.offloadSchedulingEnabled) {
      for (AudioOffloadListener listener : audioOffloadListeners) {
        listener.onExperimentalOffloadSchedulingEnabledChanged(
            newPlaybackInfo.offloadSchedulingEnabled);
      }
    }
    if (previousPlaybackInfo.sleepingForOffload != newPlaybackInfo.sleepingForOffload) {
      for (AudioOffloadListener listener : audioOffloadListeners) {
        listener.onExperimentalSleepingForOffloadChanged(newPlaybackInfo.sleepingForOffload);
      }
    }
  }

  private PositionInfo getPreviousPositionInfo(
      @DiscontinuityReason int positionDiscontinuityReason,
      PlaybackInfo oldPlaybackInfo,
      int oldMaskingMediaItemIndex) {
    @Nullable Object oldWindowUid = null;
    @Nullable Object oldPeriodUid = null;
    int oldMediaItemIndex = oldMaskingMediaItemIndex;
    int oldPeriodIndex = C.INDEX_UNSET;
    @Nullable MediaItem oldMediaItem = null;
    Timeline.Period oldPeriod = new Timeline.Period();
    if (!oldPlaybackInfo.timeline.isEmpty()) {
      oldPeriodUid = oldPlaybackInfo.periodId.periodUid;
      oldPlaybackInfo.timeline.getPeriodByUid(oldPeriodUid, oldPeriod);
      oldMediaItemIndex = oldPeriod.windowIndex;
      oldPeriodIndex = oldPlaybackInfo.timeline.getIndexOfPeriod(oldPeriodUid);
      oldWindowUid = oldPlaybackInfo.timeline.getWindow(oldMediaItemIndex, window).uid;
      oldMediaItem = window.mediaItem;
    }
    long oldPositionUs;
    long oldContentPositionUs;
    if (positionDiscontinuityReason == DISCONTINUITY_REASON_AUTO_TRANSITION) {
      oldPositionUs = oldPeriod.positionInWindowUs + oldPeriod.durationUs;
      oldContentPositionUs = oldPositionUs;
      if (oldPlaybackInfo.periodId.isAd()) {
        // The old position is the end of the previous ad.
        oldPositionUs =
            oldPeriod.getAdDurationUs(
                oldPlaybackInfo.periodId.adGroupIndex, oldPlaybackInfo.periodId.adIndexInAdGroup);
        // The ad cue point is stored in the old requested content position.
        oldContentPositionUs = getRequestedContentPositionUs(oldPlaybackInfo);
      } else if (oldPlaybackInfo.periodId.nextAdGroupIndex != C.INDEX_UNSET
          && playbackInfo.periodId.isAd()) {
        // If it's a transition from content to an ad in the same window, the old position is the
        // ad cue point that is the same as current content position.
        oldPositionUs = getRequestedContentPositionUs(playbackInfo);
        oldContentPositionUs = oldPositionUs;
      }
    } else if (oldPlaybackInfo.periodId.isAd()) {
      oldPositionUs = oldPlaybackInfo.positionUs;
      oldContentPositionUs = getRequestedContentPositionUs(oldPlaybackInfo);
    } else {
      oldPositionUs = oldPeriod.positionInWindowUs + oldPlaybackInfo.positionUs;
      oldContentPositionUs = oldPositionUs;
    }
    return new PositionInfo(
        oldWindowUid,
        oldMediaItemIndex,
        oldMediaItem,
        oldPeriodUid,
        oldPeriodIndex,
        Util.usToMs(oldPositionUs),
        Util.usToMs(oldContentPositionUs),
        oldPlaybackInfo.periodId.adGroupIndex,
        oldPlaybackInfo.periodId.adIndexInAdGroup);
  }

  private PositionInfo getPositionInfo(long discontinuityWindowStartPositionUs) {
    @Nullable Object newWindowUid = null;
    @Nullable Object newPeriodUid = null;
    int newMediaItemIndex = getCurrentMediaItemIndex();
    int newPeriodIndex = C.INDEX_UNSET;
    @Nullable MediaItem newMediaItem = null;
    if (!playbackInfo.timeline.isEmpty()) {
      newPeriodUid = playbackInfo.periodId.periodUid;
      playbackInfo.timeline.getPeriodByUid(newPeriodUid, period);
      newPeriodIndex = playbackInfo.timeline.getIndexOfPeriod(newPeriodUid);
      newWindowUid = playbackInfo.timeline.getWindow(newMediaItemIndex, window).uid;
      newMediaItem = window.mediaItem;
    }
    long positionMs = Util.usToMs(discontinuityWindowStartPositionUs);
    return new PositionInfo(
        newWindowUid,
        newMediaItemIndex,
        newMediaItem,
        newPeriodUid,
        newPeriodIndex,
        positionMs,
        /* contentPositionMs= */ playbackInfo.periodId.isAd()
            ? Util.usToMs(getRequestedContentPositionUs(playbackInfo))
            : positionMs,
        playbackInfo.periodId.adGroupIndex,
        playbackInfo.periodId.adIndexInAdGroup);
  }

  private static long getRequestedContentPositionUs(PlaybackInfo playbackInfo) {
    Timeline.Window window = new Timeline.Window();
    Timeline.Period period = new Timeline.Period();
    playbackInfo.timeline.getPeriodByUid(playbackInfo.periodId.periodUid, period);
    return playbackInfo.requestedContentPositionUs == C.TIME_UNSET
        ? playbackInfo.timeline.getWindow(period.windowIndex, window).getDefaultPositionUs()
        : period.getPositionInWindowUs() + playbackInfo.requestedContentPositionUs;
  }

  private Pair<Boolean, Integer> evaluateMediaItemTransitionReason(
      PlaybackInfo playbackInfo,
      PlaybackInfo oldPlaybackInfo,
      boolean positionDiscontinuity,
      @DiscontinuityReason int positionDiscontinuityReason,
      boolean timelineChanged) {

    Timeline oldTimeline = oldPlaybackInfo.timeline;
    Timeline newTimeline = playbackInfo.timeline;
    if (newTimeline.isEmpty() && oldTimeline.isEmpty()) {
      return new Pair<>(/* isTransitioning */ false, /* mediaItemTransitionReason */ C.INDEX_UNSET);
    } else if (newTimeline.isEmpty() != oldTimeline.isEmpty()) {
      return new Pair<>(/* isTransitioning */ true, MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED);
    }

    int oldWindowIndex =
        oldTimeline.getPeriodByUid(oldPlaybackInfo.periodId.periodUid, period).windowIndex;
    Object oldWindowUid = oldTimeline.getWindow(oldWindowIndex, window).uid;
    int newWindowIndex =
        newTimeline.getPeriodByUid(playbackInfo.periodId.periodUid, period).windowIndex;
    Object newWindowUid = newTimeline.getWindow(newWindowIndex, window).uid;
    if (!oldWindowUid.equals(newWindowUid)) {
      @Player.MediaItemTransitionReason int transitionReason;
      if (positionDiscontinuity
          && positionDiscontinuityReason == DISCONTINUITY_REASON_AUTO_TRANSITION) {
        transitionReason = MEDIA_ITEM_TRANSITION_REASON_AUTO;
      } else if (positionDiscontinuity
          && positionDiscontinuityReason == DISCONTINUITY_REASON_SEEK) {
        transitionReason = MEDIA_ITEM_TRANSITION_REASON_SEEK;
      } else if (timelineChanged) {
        transitionReason = MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED;
      } else {
        // A change in window uid must be justified by one of the reasons above.
        throw new IllegalStateException();
      }
      return new Pair<>(/* isTransitioning */ true, transitionReason);
    } else if (positionDiscontinuity
        && positionDiscontinuityReason == DISCONTINUITY_REASON_AUTO_TRANSITION
        && oldPlaybackInfo.periodId.windowSequenceNumber
            < playbackInfo.periodId.windowSequenceNumber) {
      return new Pair<>(/* isTransitioning */ true, MEDIA_ITEM_TRANSITION_REASON_REPEAT);
    }
    return new Pair<>(/* isTransitioning */ false, /* mediaItemTransitionReason */ C.INDEX_UNSET);
  }

  private void updateAvailableCommands() {
    Commands previousAvailableCommands = availableCommands;
    availableCommands = Util.getAvailableCommands(wrappingPlayer, permanentAvailableCommands);
    if (!availableCommands.equals(previousAvailableCommands)) {
      listeners.queueEvent(
          Player.EVENT_AVAILABLE_COMMANDS_CHANGED,
          listener -> listener.onAvailableCommandsChanged(availableCommands));
    }
  }

  private void setMediaSourcesInternal(
      List<MediaSource> mediaSources,
      int startWindowIndex,
      long startPositionMs,
      boolean resetToDefaultPosition) {
    int currentWindowIndex = getCurrentWindowIndexInternal();
    long currentPositionMs = getCurrentPosition();
    pendingOperationAcks++;
    if (!mediaSourceHolderSnapshots.isEmpty()) {
      removeMediaSourceHolders(
          /* fromIndex= */ 0, /* toIndexExclusive= */ mediaSourceHolderSnapshots.size());
    }
    List<MediaSourceList.MediaSourceHolder> holders =
        addMediaSourceHolders(/* index= */ 0, mediaSources);
    Timeline timeline = createMaskingTimeline();
    if (!timeline.isEmpty() && startWindowIndex >= timeline.getWindowCount()) {
      throw new IllegalSeekPositionException(timeline, startWindowIndex, startPositionMs);
    }
    // Evaluate the actual start position.
    if (resetToDefaultPosition) {
      startWindowIndex = timeline.getFirstWindowIndex(shuffleModeEnabled);
      startPositionMs = C.TIME_UNSET;
    } else if (startWindowIndex == C.INDEX_UNSET) {
      startWindowIndex = currentWindowIndex;
      startPositionMs = currentPositionMs;
    }
    PlaybackInfo newPlaybackInfo =
        maskTimelineAndPosition(
            playbackInfo,
            timeline,
            maskWindowPositionMsOrGetPeriodPositionUs(timeline, startWindowIndex, startPositionMs));
    // Mask the playback state.
    int maskingPlaybackState = newPlaybackInfo.playbackState;
    if (startWindowIndex != C.INDEX_UNSET && newPlaybackInfo.playbackState != STATE_IDLE) {
      // Position reset to startWindowIndex (results in pending initial seek).
      if (timeline.isEmpty() || startWindowIndex >= timeline.getWindowCount()) {
        // Setting an empty timeline or invalid seek transitions to ended.
        maskingPlaybackState = STATE_ENDED;
      } else {
        maskingPlaybackState = STATE_BUFFERING;
      }
    }
    newPlaybackInfo = newPlaybackInfo.copyWithPlaybackState(maskingPlaybackState);
    internalPlayer.setMediaSources(
        holders, startWindowIndex, Util.msToUs(startPositionMs), shuffleOrder);
    boolean positionDiscontinuity =
        !playbackInfo.periodId.periodUid.equals(newPlaybackInfo.periodId.periodUid)
            && !playbackInfo.timeline.isEmpty();
    updatePlaybackInfo(
        newPlaybackInfo,
        /* timelineChangeReason= */ TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED,
        /* ignored */ PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
        /* seekProcessed= */ false,
        /* positionDiscontinuity= */ positionDiscontinuity,
        DISCONTINUITY_REASON_REMOVE,
        /* discontinuityWindowStartPositionUs= */ getCurrentPositionUsInternal(newPlaybackInfo),
        /* ignored */ C.INDEX_UNSET);
  }

  private List<MediaSourceList.MediaSourceHolder> addMediaSourceHolders(
      int index, List<MediaSource> mediaSources) {
    List<MediaSourceList.MediaSourceHolder> holders = new ArrayList<>();
    for (int i = 0; i < mediaSources.size(); i++) {
      MediaSourceList.MediaSourceHolder holder =
          new MediaSourceList.MediaSourceHolder(mediaSources.get(i), useLazyPreparation);
      holders.add(holder);
      mediaSourceHolderSnapshots.add(
          i + index, new MediaSourceHolderSnapshot(holder.uid, holder.mediaSource.getTimeline()));
    }
    shuffleOrder =
        shuffleOrder.cloneAndInsert(
            /* insertionIndex= */ index, /* insertionCount= */ holders.size());
    return holders;
  }

  private PlaybackInfo removeMediaItemsInternal(int fromIndex, int toIndex) {
    Assertions.checkArgument(
        fromIndex >= 0 && toIndex >= fromIndex && toIndex <= mediaSourceHolderSnapshots.size());
    int currentIndex = getCurrentMediaItemIndex();
    Timeline oldTimeline = getCurrentTimeline();
    int currentMediaSourceCount = mediaSourceHolderSnapshots.size();
    pendingOperationAcks++;
    removeMediaSourceHolders(fromIndex, /* toIndexExclusive= */ toIndex);
    Timeline newTimeline = createMaskingTimeline();
    PlaybackInfo newPlaybackInfo =
        maskTimelineAndPosition(
            playbackInfo,
            newTimeline,
            getPeriodPositionUsAfterTimelineChanged(oldTimeline, newTimeline));
    // Player transitions to STATE_ENDED if the current index is part of the removed tail.
    final boolean transitionsToEnded =
        newPlaybackInfo.playbackState != STATE_IDLE
            && newPlaybackInfo.playbackState != STATE_ENDED
            && fromIndex < toIndex
            && toIndex == currentMediaSourceCount
            && currentIndex >= newPlaybackInfo.timeline.getWindowCount();
    if (transitionsToEnded) {
      newPlaybackInfo = newPlaybackInfo.copyWithPlaybackState(STATE_ENDED);
    }
    internalPlayer.removeMediaSources(fromIndex, toIndex, shuffleOrder);
    return newPlaybackInfo;
  }

  private void removeMediaSourceHolders(int fromIndex, int toIndexExclusive) {
    for (int i = toIndexExclusive - 1; i >= fromIndex; i--) {
      mediaSourceHolderSnapshots.remove(i);
    }
    shuffleOrder = shuffleOrder.cloneAndRemove(fromIndex, toIndexExclusive);
  }

  private Timeline createMaskingTimeline() {
    return new PlaylistTimeline(mediaSourceHolderSnapshots, shuffleOrder);
  }

  private PlaybackInfo maskTimelineAndPosition(
      PlaybackInfo playbackInfo, Timeline timeline, @Nullable Pair<Object, Long> periodPositionUs) {
    Assertions.checkArgument(timeline.isEmpty() || periodPositionUs != null);
    Timeline oldTimeline = playbackInfo.timeline;
    // Mask the timeline.
    playbackInfo = playbackInfo.copyWithTimeline(timeline);

    if (timeline.isEmpty()) {
      // Reset periodId and loadingPeriodId.
      MediaPeriodId dummyMediaPeriodId = PlaybackInfo.getDummyPeriodForEmptyTimeline();
      long positionUs = Util.msToUs(maskingWindowPositionMs);
      playbackInfo =
          playbackInfo.copyWithNewPosition(
              dummyMediaPeriodId,
              positionUs,
              /* requestedContentPositionUs= */ positionUs,
              /* discontinuityStartPositionUs= */ positionUs,
              /* totalBufferedDurationUs= */ 0,
              TrackGroupArray.EMPTY,
              emptyTrackSelectorResult,
              /* staticMetadata= */ ImmutableList.of());
      playbackInfo = playbackInfo.copyWithLoadingMediaPeriodId(dummyMediaPeriodId);
      playbackInfo.bufferedPositionUs = playbackInfo.positionUs;
      return playbackInfo;
    }

    Object oldPeriodUid = playbackInfo.periodId.periodUid;
    boolean playingPeriodChanged = !oldPeriodUid.equals(castNonNull(periodPositionUs).first);
    MediaPeriodId newPeriodId =
        playingPeriodChanged ? new MediaPeriodId(periodPositionUs.first) : playbackInfo.periodId;
    long newContentPositionUs = periodPositionUs.second;
    long oldContentPositionUs = Util.msToUs(getContentPosition());
    if (!oldTimeline.isEmpty()) {
      oldContentPositionUs -=
          oldTimeline.getPeriodByUid(oldPeriodUid, period).getPositionInWindowUs();
    }

    if (playingPeriodChanged || newContentPositionUs < oldContentPositionUs) {
      checkState(!newPeriodId.isAd());
      // The playing period changes or a backwards seek within the playing period occurs.
      playbackInfo =
          playbackInfo.copyWithNewPosition(
              newPeriodId,
              /* positionUs= */ newContentPositionUs,
              /* requestedContentPositionUs= */ newContentPositionUs,
              /* discontinuityStartPositionUs= */ newContentPositionUs,
              /* totalBufferedDurationUs= */ 0,
              playingPeriodChanged ? TrackGroupArray.EMPTY : playbackInfo.trackGroups,
              playingPeriodChanged ? emptyTrackSelectorResult : playbackInfo.trackSelectorResult,
              playingPeriodChanged ? ImmutableList.of() : playbackInfo.staticMetadata);
      playbackInfo = playbackInfo.copyWithLoadingMediaPeriodId(newPeriodId);
      playbackInfo.bufferedPositionUs = newContentPositionUs;
    } else if (newContentPositionUs == oldContentPositionUs) {
      // Period position remains unchanged.
      int loadingPeriodIndex =
          timeline.getIndexOfPeriod(playbackInfo.loadingMediaPeriodId.periodUid);
      if (loadingPeriodIndex == C.INDEX_UNSET
          || timeline.getPeriod(loadingPeriodIndex, period).windowIndex
              != timeline.getPeriodByUid(newPeriodId.periodUid, period).windowIndex) {
        // Discard periods after the playing period, if the loading period is discarded or the
        // playing and loading period are not in the same window.
        timeline.getPeriodByUid(newPeriodId.periodUid, period);
        long maskedBufferedPositionUs =
            newPeriodId.isAd()
                ? period.getAdDurationUs(newPeriodId.adGroupIndex, newPeriodId.adIndexInAdGroup)
                : period.durationUs;
        playbackInfo =
            playbackInfo.copyWithNewPosition(
                newPeriodId,
                /* positionUs= */ playbackInfo.positionUs,
                /* requestedContentPositionUs= */ playbackInfo.positionUs,
                playbackInfo.discontinuityStartPositionUs,
                /* totalBufferedDurationUs= */ maskedBufferedPositionUs - playbackInfo.positionUs,
                playbackInfo.trackGroups,
                playbackInfo.trackSelectorResult,
                playbackInfo.staticMetadata);
        playbackInfo = playbackInfo.copyWithLoadingMediaPeriodId(newPeriodId);
        playbackInfo.bufferedPositionUs = maskedBufferedPositionUs;
      }
    } else {
      checkState(!newPeriodId.isAd());
      // A forward seek within the playing period (timeline did not change).
      long maskedTotalBufferedDurationUs =
          max(
              0,
              playbackInfo.totalBufferedDurationUs - (newContentPositionUs - oldContentPositionUs));
      long maskedBufferedPositionUs = playbackInfo.bufferedPositionUs;
      if (playbackInfo.loadingMediaPeriodId.equals(playbackInfo.periodId)) {
        maskedBufferedPositionUs = newContentPositionUs + maskedTotalBufferedDurationUs;
      }
      playbackInfo =
          playbackInfo.copyWithNewPosition(
              newPeriodId,
              /* positionUs= */ newContentPositionUs,
              /* requestedContentPositionUs= */ newContentPositionUs,
              /* discontinuityStartPositionUs= */ newContentPositionUs,
              maskedTotalBufferedDurationUs,
              playbackInfo.trackGroups,
              playbackInfo.trackSelectorResult,
              playbackInfo.staticMetadata);
      playbackInfo.bufferedPositionUs = maskedBufferedPositionUs;
    }
    return playbackInfo;
  }

  @Nullable
  private Pair<Object, Long> getPeriodPositionUsAfterTimelineChanged(
      Timeline oldTimeline, Timeline newTimeline) {
    long currentPositionMs = getContentPosition();
    if (oldTimeline.isEmpty() || newTimeline.isEmpty()) {
      boolean isCleared = !oldTimeline.isEmpty() && newTimeline.isEmpty();
      return maskWindowPositionMsOrGetPeriodPositionUs(
          newTimeline,
          isCleared ? C.INDEX_UNSET : getCurrentWindowIndexInternal(),
          isCleared ? C.TIME_UNSET : currentPositionMs);
    }
    int currentMediaItemIndex = getCurrentMediaItemIndex();
    @Nullable
    Pair<Object, Long> oldPeriodPositionUs =
        oldTimeline.getPeriodPositionUs(
            window, period, currentMediaItemIndex, Util.msToUs(currentPositionMs));
    Object periodUid = castNonNull(oldPeriodPositionUs).first;
    if (newTimeline.getIndexOfPeriod(periodUid) != C.INDEX_UNSET) {
      // The old period position is still available in the new timeline.
      return oldPeriodPositionUs;
    }
    // Period uid not found in new timeline. Try to get subsequent period.
    @Nullable
    Object nextPeriodUid =
        ExoPlayerImplInternal.resolveSubsequentPeriod(
            window, period, repeatMode, shuffleModeEnabled, periodUid, oldTimeline, newTimeline);
    if (nextPeriodUid != null) {
      // Reset position to the default position of the window of the subsequent period.
      newTimeline.getPeriodByUid(nextPeriodUid, period);
      return maskWindowPositionMsOrGetPeriodPositionUs(
          newTimeline,
          period.windowIndex,
          newTimeline.getWindow(period.windowIndex, window).getDefaultPositionMs());
    } else {
      // No subsequent period found and the new timeline is not empty. Use the default position.
      return maskWindowPositionMsOrGetPeriodPositionUs(
          newTimeline, /* windowIndex= */ C.INDEX_UNSET, /* windowPositionMs= */ C.TIME_UNSET);
    }
  }

  @Nullable
  private Pair<Object, Long> maskWindowPositionMsOrGetPeriodPositionUs(
      Timeline timeline, int windowIndex, long windowPositionMs) {
    if (timeline.isEmpty()) {
      // If empty we store the initial seek in the masking variables.
      maskingWindowIndex = windowIndex;
      maskingWindowPositionMs = windowPositionMs == C.TIME_UNSET ? 0 : windowPositionMs;
      maskingPeriodIndex = 0;
      return null;
    }
    if (windowIndex == C.INDEX_UNSET || windowIndex >= timeline.getWindowCount()) {
      // Use default position of timeline if window index still unset or if a previous initial seek
      // now turns out to be invalid.
      windowIndex = timeline.getFirstWindowIndex(shuffleModeEnabled);
      windowPositionMs = timeline.getWindow(windowIndex, window).getDefaultPositionMs();
    }
    return timeline.getPeriodPositionUs(window, period, windowIndex, Util.msToUs(windowPositionMs));
  }

  private long periodPositionUsToWindowPositionUs(
      Timeline timeline, MediaPeriodId periodId, long positionUs) {
    timeline.getPeriodByUid(periodId.periodUid, period);
    positionUs += period.getPositionInWindowUs();
    return positionUs;
  }

  /**
   * Builds a {@link MediaMetadata} from the main sources.
   *
   * <p>{@link MediaItem} {@link MediaMetadata} is prioritized, with any gaps/missing fields
   * populated by metadata from static ({@link TrackGroup} {@link Format}) and dynamic ({@link
   * #onMetadata(Metadata)}) sources.
   */
  private MediaMetadata buildUpdatedMediaMetadata() {
    Timeline timeline = getCurrentTimeline();
    if (timeline.isEmpty()) {
      return staticAndDynamicMediaMetadata;
    }
    MediaItem mediaItem = timeline.getWindow(getCurrentMediaItemIndex(), window).mediaItem;
    // MediaItem metadata is prioritized over metadata within the media.
    return staticAndDynamicMediaMetadata.buildUpon().populate(mediaItem.mediaMetadata).build();
  }

  private static boolean isPlaying(PlaybackInfo playbackInfo) {
    return playbackInfo.playbackState == Player.STATE_READY
        && playbackInfo.playWhenReady
        && playbackInfo.playbackSuppressionReason == PLAYBACK_SUPPRESSION_REASON_NONE;
  }

  private static final class MediaSourceHolderSnapshot implements MediaSourceInfoHolder {

    private final Object uid;

    private Timeline timeline;

    public MediaSourceHolderSnapshot(Object uid, Timeline timeline) {
      this.uid = uid;
      this.timeline = timeline;
    }

    @Override
    public Object getUid() {
      return uid;
    }

    @Override
    public Timeline getTimeline() {
      return timeline;
    }
  }

  @RequiresApi(31)
  private static final class Api31 {
    private Api31() {}

    @DoNotInline
    public static PlayerId createPlayerId() {
      // TODO: Create a MediaMetricsListener and obtain LogSessionId from it.
      return new PlayerId(LogSessionId.LOG_SESSION_ID_NONE);
    }
  }
}
