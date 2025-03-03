/*
 * Copyright 2018 The Android Open Source Project
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
package androidx.media3.session;

import static androidx.media3.common.Player.STATE_IDLE;
import static androidx.media3.test.session.common.TestUtils.LONG_TIMEOUT_MS;
import static androidx.media3.test.session.common.TestUtils.TIMEOUT_MS;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.Assert.assertThrows;

import android.content.Context;
import android.os.Bundle;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.text.TextUtils;
import androidx.media.MediaSessionManager;
import androidx.media3.common.MediaLibraryInfo;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.Util;
import androidx.media3.session.MediaSession.SessionCallback;
import androidx.media3.test.session.common.HandlerThreadTestRule;
import androidx.media3.test.session.common.MainLooperTestRule;
import androidx.media3.test.session.common.TestHandler;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link MediaSession}. */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class MediaSessionTest {

  private static final String TAG = "MediaSessionTest";

  @ClassRule public static MainLooperTestRule mainLooperTestRule = new MainLooperTestRule();

  @Rule public final HandlerThreadTestRule threadTestRule = new HandlerThreadTestRule(TAG);

  @Rule public final RemoteControllerTestRule controllerTestRule = new RemoteControllerTestRule();

  @Rule public final MediaSessionTestRule sessionTestRule = new MediaSessionTestRule();

  private Context context;
  private TestHandler handler;
  private MediaSession session;
  private MediaController controller;
  private MockPlayer player;

  @Before
  public void setUp() throws Exception {
    context = ApplicationProvider.getApplicationContext();
    handler = threadTestRule.getHandler();
    player =
        new MockPlayer.Builder().setLatchCount(1).setApplicationLooper(handler.getLooper()).build();

    session =
        sessionTestRule.ensureReleaseAfterTest(
            new MediaSession.Builder(context, player)
                .setId(TAG)
                .setSessionCallback(
                    new SessionCallback() {
                      @Override
                      public MediaSession.ConnectionResult onConnect(
                          MediaSession session, MediaSession.ControllerInfo controller) {
                        if (TextUtils.equals(
                            context.getPackageName(), controller.getPackageName())) {
                          return SessionCallback.super.onConnect(session, controller);
                        }
                        return MediaSession.ConnectionResult.reject();
                      }
                    })
                .build());

    controller =
        new MediaController.Builder(context, session.getToken())
            .setListener(new MediaController.Listener() {})
            .setApplicationLooper(threadTestRule.getHandler().getLooper())
            .buildAsync()
            .get(TIMEOUT_MS, MILLISECONDS);
  }

  @Test
  public void builder() {
    MediaSession.Builder builder;
    try {
      builder = new MediaSession.Builder(context, null);
      assertWithMessage("null player shouldn't be allowed").fail();
    } catch (NullPointerException e) {
      // expected. pass-through
    }
    try {
      builder = new MediaSession.Builder(context, controller);
      assertWithMessage("MediaController shouldn't be allowed").fail();
    } catch (IllegalArgumentException e) {
      // expected. pass-through
    }
    try {
      builder = new MediaSession.Builder(context, player);
      builder.setId(null);
      assertWithMessage("null id shouldn't be allowed").fail();
    } catch (NullPointerException e) {
      // expected. pass-through
    }
    try {
      builder = new MediaSession.Builder(context, player);
      builder.setExtras(null);
      assertWithMessage("null extras shouldn't be allowed").fail();
    } catch (NullPointerException e) {
      // expected. pass-through
    }
    // Empty string as ID is allowed.
    new MediaSession.Builder(context, player).setId("").build().release();
  }

  @Test
  public void getPlayer() throws Exception {
    assertThat(handler.postAndSync(session::getPlayer)).isEqualTo(player);
  }

  @Test
  public void setPlayer_withNewPlayer_changesPlayer() throws Exception {
    MockPlayer player = new MockPlayer.Builder().setApplicationLooper(handler.getLooper()).build();
    handler.postAndSync(() -> session.setPlayer(player));
    assertThat(handler.postAndSync(session::getPlayer)).isEqualTo(player);
  }

  @Test
  public void setPlayer_withTheSamePlayer_doesNothing() throws Exception {
    handler.postAndSync(() -> session.setPlayer(player));
    assertThat(handler.postAndSync(session::getPlayer)).isEqualTo(player);
  }

  @Test
  public void setPlayer_withDifferentLooper_throwsIAE() throws Exception {
    MockPlayer player =
        new MockPlayer.Builder().setApplicationLooper(Looper.getMainLooper()).build();
    assertThrows(
        IllegalArgumentException.class, () -> handler.postAndSync(() -> session.setPlayer(player)));
  }

  @Test
  public void setPlayer_withMediaController_throwsIAE() throws Exception {
    assertThrows(
        IllegalArgumentException.class,
        () -> handler.postAndSync(() -> session.setPlayer(controller)));
  }

  @Test
  public void setPlayer_fromDifferentLooper_throwsISE() throws Exception {
    MockPlayer player = new MockPlayer.Builder().setApplicationLooper(handler.getLooper()).build();
    assertThrows(IllegalStateException.class, () -> session.setPlayer(player));
  }

  /** Test potential deadlock for calls between controller and session. */
  @Test
  @LargeTest
  public void deadlock() throws Exception {
    handler.postAndSync(
        () -> {
          session.release();
          session = null;
        });

    HandlerThread testThread = new HandlerThread("deadlock");
    testThread.start();
    TestHandler testHandler = new TestHandler(testThread.getLooper());
    try {
      MockPlayer player =
          new MockPlayer.Builder().setApplicationLooper(testThread.getLooper()).build();
      handler.postAndSync(
          () -> {
            session = new MediaSession.Builder(context, player).setId("testDeadlock").build();
          });
      RemoteMediaController controller =
          controllerTestRule.createRemoteController(session.getToken());
      // This may hang if deadlock happens.
      testHandler.postAndSync(
          () -> {
            int state = STATE_IDLE;
            for (int i = 0; i < 100; i++) {
              Log.d(TAG, "testDeadlock for-loop started: index=" + i);
              long startTime = SystemClock.elapsedRealtime();

              // triggers call from session to controller.
              player.notifyPlaybackStateChanged(state);
              long endTime = SystemClock.elapsedRealtime();
              Log.d(TAG, "1) Time spent on API call(ms): " + (endTime - startTime));

              // triggers call from controller to session.
              startTime = endTime;
              controller.play();
              endTime = SystemClock.elapsedRealtime();
              Log.d(TAG, "2) Time spent on API call(ms): " + (endTime - startTime));

              // Repeat above
              startTime = endTime;
              player.notifyPlaybackStateChanged(state);
              endTime = SystemClock.elapsedRealtime();
              Log.d(TAG, "3) Time spent on API call(ms): " + (endTime - startTime));

              startTime = endTime;
              controller.pause();
              endTime = SystemClock.elapsedRealtime();
              Log.d(TAG, "4) Time spent on API call(ms): " + (endTime - startTime));

              startTime = endTime;
              player.notifyPlaybackStateChanged(state);
              endTime = SystemClock.elapsedRealtime();
              Log.d(TAG, "5) Time spent on API call(ms): " + (endTime - startTime));

              startTime = endTime;
              controller.seekTo(0);
              endTime = SystemClock.elapsedRealtime();
              Log.d(TAG, "6) Time spent on API call(ms): " + (endTime - startTime));

              startTime = endTime;
              player.notifyPlaybackStateChanged(state);
              endTime = SystemClock.elapsedRealtime();
              Log.d(TAG, "7) Time spent on API call(ms): " + (endTime - startTime));

              startTime = endTime;
              controller.seekToNextMediaItem();
              endTime = SystemClock.elapsedRealtime();
              Log.d(TAG, "8) Time spent on API call(ms): " + (endTime - startTime));

              startTime = endTime;
              player.notifyPlaybackStateChanged(state);
              endTime = SystemClock.elapsedRealtime();
              Log.d(TAG, "9) Time spent on API call(ms): " + (endTime - startTime));

              startTime = endTime;
              controller.seekToPreviousMediaItem();
              endTime = SystemClock.elapsedRealtime();
              Log.d(TAG, "10) Time spent on API call(ms): " + (endTime - startTime));
            }
          },
          LONG_TIMEOUT_MS);
    } finally {
      if (session != null) {
        handler.postAndSync(
            () -> {
              // Clean up here because sessionHandler will be removed afterwards.
              session.release();
              session = null;
            });
      }

      if (Util.SDK_INT >= 18) {
        testThread.quitSafely();
      } else {
        testThread.quit();
      }
    }
  }

  @Test
  public void creatingTwoSessionWithSameId() {
    String sessionId = "testSessionId";
    MediaSession session =
        new MediaSession.Builder(
                context, new MockPlayer.Builder().setApplicationLooper(handler.getLooper()).build())
            .setId(sessionId)
            .build();

    MediaSession.Builder builderWithSameId =
        new MediaSession.Builder(
            context, new MockPlayer.Builder().setApplicationLooper(handler.getLooper()).build());
    try {
      builderWithSameId.setId(sessionId).build();
      assertWithMessage(
              "Creating a new session with the same ID in a process should not be allowed")
          .fail();
    } catch (IllegalStateException e) {
      // expected. pass-through
    }

    session.release();
    // Creating a new session with ID of the closed session is okay.
    MediaSession sessionWithSameId = builderWithSameId.build();
    sessionWithSameId.release();
  }

  @Test
  public void sendCustomCommand_onConnect() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    SessionCommand testCommand = new SessionCommand("test", /* extras= */ Bundle.EMPTY);
    SessionCallback testSessionCallback =
        new SessionCallback() {
          @Override
          public MediaSession.ConnectionResult onConnect(
              MediaSession session, MediaSession.ControllerInfo controller) {
            Future<SessionResult> result =
                session.sendCustomCommand(controller, testCommand, /* args= */ Bundle.EMPTY);
            try {
              // The controller is not connected yet.
              assertThat(result.get(TIMEOUT_MS, MILLISECONDS).resultCode)
                  .isEqualTo(SessionResult.RESULT_ERROR_SESSION_DISCONNECTED);
            } catch (ExecutionException | InterruptedException | TimeoutException e) {
              assertWithMessage("Fail to get result of the returned future.").fail();
            }
            return SessionCallback.super.onConnect(session, controller);
          }

          @Override
          public void onPostConnect(MediaSession session, MediaSession.ControllerInfo controller) {
            Future<SessionResult> result =
                session.sendCustomCommand(controller, testCommand, /* args= */ Bundle.EMPTY);
            try {
              // The controller is connected but doesn't implement onCustomCommand.
              assertThat(result.get(TIMEOUT_MS, MILLISECONDS).resultCode)
                  .isEqualTo(SessionResult.RESULT_ERROR_NOT_SUPPORTED);
            } catch (ExecutionException | InterruptedException | TimeoutException e) {
              assertWithMessage("Fail to get result of the returned future.").fail();
            }
            latch.countDown();
          }
        };
    MediaSession session =
        sessionTestRule.ensureReleaseAfterTest(
            new MediaSession.Builder(context, player)
                .setSessionCallback(testSessionCallback)
                .build());
    controllerTestRule.createRemoteController(session.getToken());
    assertThat(latch.await(TIMEOUT_MS, MILLISECONDS)).isTrue();
  }

  /** Test {@link MediaSession#getSessionCompatToken()}. */
  @Test
  public void getSessionCompatToken_returnsCompatibleWithMediaControllerCompat() throws Exception {
    String expectedControllerCompatPackageName =
        (21 <= Util.SDK_INT && Util.SDK_INT < 24)
            ? MediaSessionManager.RemoteUserInfo.LEGACY_CONTROLLER
            : context.getPackageName();
    MediaSession session =
        sessionTestRule.ensureReleaseAfterTest(
            new MediaSession.Builder(context, player)
                .setId("getSessionCompatToken_returnsCompatibleWithMediaControllerCompat")
                .setSessionCallback(
                    new SessionCallback() {
                      @Override
                      public MediaSession.ConnectionResult onConnect(
                          MediaSession session, MediaSession.ControllerInfo controller) {
                        if (TextUtils.equals(
                            expectedControllerCompatPackageName, controller.getPackageName())) {
                          return SessionCallback.super.onConnect(session, controller);
                        }
                        return MediaSession.ConnectionResult.reject();
                      }
                    })
                .build());
    Object token = session.getSessionCompatToken();
    assertThat(token).isInstanceOf(MediaSessionCompat.Token.class);
    MediaControllerCompat controllerCompat =
        new MediaControllerCompat(context, (MediaSessionCompat.Token) token);
    CountDownLatch sessionReadyLatch = new CountDownLatch(1);
    controllerCompat.registerCallback(
        new MediaControllerCompat.Callback() {
          @Override
          public void onSessionReady() {
            sessionReadyLatch.countDown();
          }
        },
        handler);
    assertThat(sessionReadyLatch.await(TIMEOUT_MS, MILLISECONDS)).isTrue();

    long testSeekPositionMs = 1234;
    controllerCompat.getTransportControls().seekTo(testSeekPositionMs);

    assertThat(player.countDownLatch.await(TIMEOUT_MS, MILLISECONDS)).isTrue();
    assertThat(player.seekToCalled).isTrue();
    assertThat(player.seekPositionMs).isEqualTo(testSeekPositionMs);
  }

  @Test
  public void getControllerVersion() throws Exception {
    CountDownLatch connectedLatch = new CountDownLatch(1);
    AtomicInteger controllerVersionRef = new AtomicInteger();
    SessionCallback sessionCallback =
        new SessionCallback() {
          @Override
          public MediaSession.ConnectionResult onConnect(
              MediaSession session, MediaSession.ControllerInfo controller) {
            controllerVersionRef.set(controller.getControllerVersion());
            connectedLatch.countDown();
            return SessionCallback.super.onConnect(session, controller);
          }
        };

    MediaSession session =
        sessionTestRule.ensureReleaseAfterTest(
            new MediaSession.Builder(context, player)
                .setId("getControllerVersion")
                .setSessionCallback(sessionCallback)
                .build());
    controllerTestRule.createRemoteController(session.getToken());

    assertThat(connectedLatch.await(TIMEOUT_MS, MILLISECONDS)).isTrue();
    // TODO(b/199226670): The expected version should vary if the test runs with the previous
    //  version of remote controller.
    assertThat(controllerVersionRef.get()).isEqualTo(MediaLibraryInfo.VERSION_INT);
  }
}
