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
package androidx.media3.exoplayer.hls;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import android.net.Uri;
import androidx.media3.common.Format;
import androidx.media3.exoplayer.SeekParameters;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser;
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistTracker;
import androidx.media3.test.utils.ExoPlayerTestRunner;
import androidx.media3.test.utils.FakeDataSource;
import androidx.media3.test.utils.TestUtil;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.IOException;
import java.io.InputStream;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;

/** Unit tests for {@link HlsChunkSource}. */
@RunWith(AndroidJUnit4.class)
public class HlsChunkSourceTest {

  private static final String PLAYLIST = "media/m3u8/media_playlist";
  private static final String PLAYLIST_INDEPENDENT_SEGMENTS =
      "media/m3u8/media_playlist_independent_segments";
  private static final String PLAYLIST_EMPTY = "media/m3u8/media_playlist_empty";
  private static final Uri PLAYLIST_URI = Uri.parse("http://example.com/");
  private static final long PLAYLIST_START_PERIOD_OFFSET_US = 8_000_000L;

  private final HlsExtractorFactory mockExtractorFactory = HlsExtractorFactory.DEFAULT;

  @Mock private HlsPlaylistTracker mockPlaylistTracker;
  private HlsChunkSource testChunkSource;

  @Before
  public void setup() throws IOException {
    mockPlaylistTracker = Mockito.mock(HlsPlaylistTracker.class);

    InputStream inputStream =
        TestUtil.getInputStream(
            ApplicationProvider.getApplicationContext(), PLAYLIST_INDEPENDENT_SEGMENTS);
    HlsMediaPlaylist playlist =
        (HlsMediaPlaylist) new HlsPlaylistParser().parse(PLAYLIST_URI, inputStream);
    when(mockPlaylistTracker.getPlaylistSnapshot(eq(PLAYLIST_URI), anyBoolean()))
        .thenReturn(playlist);

    testChunkSource =
        new HlsChunkSource(
            mockExtractorFactory,
            mockPlaylistTracker,
            new Uri[] {PLAYLIST_URI},
            new Format[] {ExoPlayerTestRunner.VIDEO_FORMAT},
            new DefaultHlsDataSourceFactory(new FakeDataSource.Factory()),
            /* mediaTransferListener= */ null,
            new TimestampAdjusterProvider(),
            /* muxedCaptionFormats= */ null,
            PlayerId.UNSET);

    when(mockPlaylistTracker.isSnapshotValid(eq(PLAYLIST_URI))).thenReturn(true);
    // Mock that segments totalling PLAYLIST_START_PERIOD_OFFSET_US in duration have been removed
    // from the start of the playlist.
    when(mockPlaylistTracker.getInitialStartTimeUs())
        .thenReturn(playlist.startTimeUs - PLAYLIST_START_PERIOD_OFFSET_US);
  }

  @Test
  public void getAdjustedSeekPositionUs_previousSync() {
    long adjustedPositionUs =
        testChunkSource.getAdjustedSeekPositionUs(
            playlistTimeToPeriodTimeUs(17_000_000), SeekParameters.PREVIOUS_SYNC);

    assertThat(periodTimeToPlaylistTimeUs(adjustedPositionUs)).isEqualTo(16_000_000);
  }

  @Test
  public void getAdjustedSeekPositionUs_nextSync() {
    long adjustedPositionUs =
        testChunkSource.getAdjustedSeekPositionUs(
            playlistTimeToPeriodTimeUs(17_000_000), SeekParameters.NEXT_SYNC);

    assertThat(periodTimeToPlaylistTimeUs(adjustedPositionUs)).isEqualTo(20_000_000);
  }

  @Test
  public void getAdjustedSeekPositionUs_nextSyncAtEnd() {
    long adjustedPositionUs =
        testChunkSource.getAdjustedSeekPositionUs(
            playlistTimeToPeriodTimeUs(24_000_000), SeekParameters.NEXT_SYNC);

    assertThat(periodTimeToPlaylistTimeUs(adjustedPositionUs)).isEqualTo(24_000_000);
  }

  @Test
  public void getAdjustedSeekPositionUs_closestSyncBefore() {
    long adjustedPositionUs =
        testChunkSource.getAdjustedSeekPositionUs(
            playlistTimeToPeriodTimeUs(17_000_000), SeekParameters.CLOSEST_SYNC);

    assertThat(periodTimeToPlaylistTimeUs(adjustedPositionUs)).isEqualTo(16_000_000);
  }

  @Test
  public void getAdjustedSeekPositionUs_closestSyncAfter() {
    long adjustedPositionUs =
        testChunkSource.getAdjustedSeekPositionUs(
            playlistTimeToPeriodTimeUs(19_000_000), SeekParameters.CLOSEST_SYNC);

    assertThat(periodTimeToPlaylistTimeUs(adjustedPositionUs)).isEqualTo(20_000_000);
  }

  @Test
  public void getAdjustedSeekPositionUs_exact() {
    long adjustedPositionUs =
        testChunkSource.getAdjustedSeekPositionUs(
            playlistTimeToPeriodTimeUs(17_000_000), SeekParameters.EXACT);

    assertThat(periodTimeToPlaylistTimeUs(adjustedPositionUs)).isEqualTo(17_000_000);
  }

  @Test
  public void getAdjustedSeekPositionUs_noIndependentSegments() throws IOException {
    InputStream inputStream =
        TestUtil.getInputStream(ApplicationProvider.getApplicationContext(), PLAYLIST);
    HlsMediaPlaylist playlist =
        (HlsMediaPlaylist) new HlsPlaylistParser().parse(PLAYLIST_URI, inputStream);
    when(mockPlaylistTracker.getPlaylistSnapshot(eq(PLAYLIST_URI), anyBoolean()))
        .thenReturn(playlist);

    long adjustedPositionUs =
        testChunkSource.getAdjustedSeekPositionUs(
            playlistTimeToPeriodTimeUs(100_000_000), SeekParameters.EXACT);

    assertThat(periodTimeToPlaylistTimeUs(adjustedPositionUs)).isEqualTo(100_000_000);
  }

  @Test
  public void getAdjustedSeekPositionUs_emptyPlaylist() throws IOException {
    InputStream inputStream =
        TestUtil.getInputStream(ApplicationProvider.getApplicationContext(), PLAYLIST_EMPTY);
    HlsMediaPlaylist playlist =
        (HlsMediaPlaylist) new HlsPlaylistParser().parse(PLAYLIST_URI, inputStream);
    when(mockPlaylistTracker.getPlaylistSnapshot(eq(PLAYLIST_URI), anyBoolean()))
        .thenReturn(playlist);

    long adjustedPositionUs =
        testChunkSource.getAdjustedSeekPositionUs(
            playlistTimeToPeriodTimeUs(100_000_000), SeekParameters.EXACT);

    assertThat(periodTimeToPlaylistTimeUs(adjustedPositionUs)).isEqualTo(100_000_000);
  }

  private static long playlistTimeToPeriodTimeUs(long playlistTimeUs) {
    return playlistTimeUs + PLAYLIST_START_PERIOD_OFFSET_US;
  }

  private static long periodTimeToPlaylistTimeUs(long periodTimeUs) {
    return periodTimeUs - PLAYLIST_START_PERIOD_OFFSET_US;
  }
}
