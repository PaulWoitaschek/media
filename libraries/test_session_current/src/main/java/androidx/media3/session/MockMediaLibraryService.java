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

import static androidx.media3.common.util.Assertions.checkNotNull;
import static androidx.media3.session.MediaTestUtils.assertLibraryParamsEquals;
import static androidx.media3.test.session.common.CommonConstants.SUPPORT_APP_PACKAGE_NAME;
import static androidx.media3.test.session.common.MediaBrowserConstants.CUSTOM_ACTION;
import static androidx.media3.test.session.common.MediaBrowserConstants.CUSTOM_ACTION_ASSERT_PARAMS;
import static androidx.media3.test.session.common.MediaBrowserConstants.CUSTOM_ACTION_EXTRAS;
import static androidx.media3.test.session.common.MediaBrowserConstants.GET_CHILDREN_RESULT;
import static androidx.media3.test.session.common.MediaBrowserConstants.LONG_LIST_COUNT;
import static androidx.media3.test.session.common.MediaBrowserConstants.MEDIA_ID_GET_BROWSABLE_ITEM;
import static androidx.media3.test.session.common.MediaBrowserConstants.MEDIA_ID_GET_ITEM_WITH_METADATA;
import static androidx.media3.test.session.common.MediaBrowserConstants.MEDIA_ID_GET_NULL_ITEM;
import static androidx.media3.test.session.common.MediaBrowserConstants.MEDIA_ID_GET_PLAYABLE_ITEM;
import static androidx.media3.test.session.common.MediaBrowserConstants.NOTIFY_CHILDREN_CHANGED_EXTRAS;
import static androidx.media3.test.session.common.MediaBrowserConstants.NOTIFY_CHILDREN_CHANGED_ITEM_COUNT;
import static androidx.media3.test.session.common.MediaBrowserConstants.PARENT_ID;
import static androidx.media3.test.session.common.MediaBrowserConstants.PARENT_ID_ERROR;
import static androidx.media3.test.session.common.MediaBrowserConstants.PARENT_ID_LONG_LIST;
import static androidx.media3.test.session.common.MediaBrowserConstants.ROOT_EXTRAS;
import static androidx.media3.test.session.common.MediaBrowserConstants.ROOT_ID;
import static androidx.media3.test.session.common.MediaBrowserConstants.SEARCH_QUERY;
import static androidx.media3.test.session.common.MediaBrowserConstants.SEARCH_QUERY_EMPTY_RESULT;
import static androidx.media3.test.session.common.MediaBrowserConstants.SEARCH_QUERY_LONG_LIST;
import static androidx.media3.test.session.common.MediaBrowserConstants.SEARCH_QUERY_TAKES_TIME;
import static androidx.media3.test.session.common.MediaBrowserConstants.SEARCH_RESULT;
import static androidx.media3.test.session.common.MediaBrowserConstants.SEARCH_RESULT_COUNT;
import static androidx.media3.test.session.common.MediaBrowserConstants.SEARCH_TIME_IN_MS;
import static androidx.media3.test.session.common.MediaBrowserConstants.SUBSCRIBE_ID_NOTIFY_CHILDREN_CHANGED_TO_ALL;
import static androidx.media3.test.session.common.MediaBrowserConstants.SUBSCRIBE_ID_NOTIFY_CHILDREN_CHANGED_TO_ALL_WITH_NON_SUBSCRIBED_ID;
import static androidx.media3.test.session.common.MediaBrowserConstants.SUBSCRIBE_ID_NOTIFY_CHILDREN_CHANGED_TO_ONE;
import static androidx.media3.test.session.common.MediaBrowserConstants.SUBSCRIBE_ID_NOTIFY_CHILDREN_CHANGED_TO_ONE_WITH_NON_SUBSCRIBED_ID;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import android.app.Service;
import android.content.Context;
import android.os.Bundle;
import android.os.HandlerThread;
import androidx.annotation.GuardedBy;
import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.util.BundleableUtil;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.session.MediaLibraryService.MediaLibrarySession.MediaLibrarySessionCallback;
import androidx.media3.session.MediaSession.ControllerInfo;
import androidx.media3.test.session.common.TestHandler;
import androidx.media3.test.session.common.TestUtils;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

/** A mock MediaLibraryService */
@UnstableApi
public class MockMediaLibraryService extends MediaLibraryService {
  /** ID of the session that this service will create. */
  public static final String ID = "TestLibrary";

  public static final MediaItem ROOT_ITEM =
      new MediaItem.Builder()
          .setMediaId(ROOT_ID)
          .setMediaMetadata(
              new MediaMetadata.Builder()
                  .setFolderType(MediaMetadata.FOLDER_TYPE_MIXED)
                  .setIsPlayable(false)
                  .build())
          .build();
  public static final LibraryParams ROOT_PARAMS =
      new LibraryParams.Builder().setExtras(ROOT_EXTRAS).build();
  private static final LibraryParams NOTIFY_CHILDREN_CHANGED_PARAMS =
      new LibraryParams.Builder().setExtras(NOTIFY_CHILDREN_CHANGED_EXTRAS).build();

  private static final String TAG = "MockMediaLibrarySvc2";

  @GuardedBy("MockMediaLibraryService.class")
  private static boolean assertLibraryParams;

  @GuardedBy("MockMediaLibraryService.class")
  private static LibraryParams expectedParams;

  MediaLibrarySession session;
  TestHandler handler;
  HandlerThread handlerThread;

  @Override
  public void onCreate() {
    TestServiceRegistry.getInstance().setServiceInstance(this);
    super.onCreate();
    handlerThread = new HandlerThread(TAG);
    handlerThread.start();
    handler = new TestHandler(handlerThread.getLooper());
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    synchronized (MockMediaLibraryService.class) {
      assertLibraryParams = false;
      expectedParams = null;
    }
    TestServiceRegistry.getInstance().cleanUp();
    if (Util.SDK_INT >= 18) {
      handler.getLooper().quitSafely();
    } else {
      handler.getLooper().quit();
    }
  }

  @Override
  public MediaLibrarySession onGetSession(ControllerInfo controllerInfo) {
    TestServiceRegistry registry = TestServiceRegistry.getInstance();
    TestServiceRegistry.OnGetSessionHandler onGetSessionHandler = registry.getOnGetSessionHandler();
    if (onGetSessionHandler != null) {
      return (MediaLibrarySession) onGetSessionHandler.onGetSession(controllerInfo);
    }

    MockPlayer player =
        new MockPlayer.Builder().setLatchCount(1).setApplicationLooper(handler.getLooper()).build();

    MediaLibrarySessionCallback callback = registry.getSessionCallback();
    session =
        new MediaLibrarySession.Builder(
                MockMediaLibraryService.this,
                player,
                callback != null ? callback : new TestLibrarySessionCallback())
            .setId(ID)
            .build();
    return session;
  }

  /**
   * This changes the visibility of {@link Service#attachBaseContext(Context)} to public. This is a
   * workaround for creating {@link MediaLibrarySession} without starting a service.
   */
  @Override
  public void attachBaseContext(Context base) {
    super.attachBaseContext(base);
  }

  public static void setAssertLibraryParams(LibraryParams expectedParams) {
    synchronized (MockMediaLibraryService.class) {
      assertLibraryParams = true;
      MockMediaLibraryService.expectedParams = expectedParams;
    }
  }

  private class TestLibrarySessionCallback implements MediaLibrarySessionCallback {

    @Override
    public MediaSession.ConnectionResult onConnect(
        MediaSession session, ControllerInfo controller) {
      if (!SUPPORT_APP_PACKAGE_NAME.equals(controller.getPackageName())) {
        return MediaSession.ConnectionResult.reject();
      }
      MediaSession.ConnectionResult connectionResult =
          checkNotNull(MediaLibrarySessionCallback.super.onConnect(session, controller));
      SessionCommands.Builder builder = connectionResult.availableSessionCommands.buildUpon();
      builder.add(new SessionCommand(CUSTOM_ACTION, /* extras= */ Bundle.EMPTY));
      builder.add(new SessionCommand(CUSTOM_ACTION_ASSERT_PARAMS, /* extras= */ Bundle.EMPTY));
      return MediaSession.ConnectionResult.accept(
          /* availableSessionCommands= */ builder.build(),
          connectionResult.availablePlayerCommands);
    }

    @Override
    public ListenableFuture<LibraryResult<MediaItem>> onGetLibraryRoot(
        MediaLibrarySession session, ControllerInfo browser, @Nullable LibraryParams params) {
      assertLibraryParams(params);
      return Futures.immediateFuture(LibraryResult.ofItem(ROOT_ITEM, ROOT_PARAMS));
    }

    @Override
    public ListenableFuture<LibraryResult<MediaItem>> onGetItem(
        MediaLibrarySession session, ControllerInfo browser, String mediaId) {
      switch (mediaId) {
        case MEDIA_ID_GET_BROWSABLE_ITEM:
          return Futures.immediateFuture(
              LibraryResult.ofItem(createBrowsableMediaItem(mediaId), /* params= */ null));
        case MEDIA_ID_GET_PLAYABLE_ITEM:
          return Futures.immediateFuture(
              LibraryResult.ofItem(createPlayableMediaItem(mediaId), /* params= */ null));
        case MEDIA_ID_GET_ITEM_WITH_METADATA:
          return Futures.immediateFuture(
              LibraryResult.ofItem(createMediaItemWithMetadata(mediaId), /* params= */ null));
        case MEDIA_ID_GET_NULL_ITEM:
          // Passing item=null here is expected to throw NPE, this is testing a misbehaving app
          // that ignores the nullness annotations.
          return Futures.immediateFuture(
              LibraryResult.ofItem(/* item= */ null, /* params= */ null));
        default: // fall out
      }
      return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE));
    }

    @Override
    public ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> onGetChildren(
        MediaLibrarySession session,
        ControllerInfo browser,
        String parentId,
        int page,
        int pageSize,
        @Nullable LibraryParams params) {
      assertLibraryParams(params);
      if (PARENT_ID.equals(parentId)) {
        return Futures.immediateFuture(
            LibraryResult.ofItemList(
                getPaginatedResult(GET_CHILDREN_RESULT, page, pageSize), params));
      } else if (PARENT_ID_LONG_LIST.equals(parentId)) {
        List<MediaItem> list = new ArrayList<>(LONG_LIST_COUNT);
        for (int i = 0; i < LONG_LIST_COUNT; i++) {
          list.add(createPlayableMediaItem(TestUtils.getMediaIdInFakeTimeline(i)));
        }
        return Futures.immediateFuture(LibraryResult.ofItemList(list, params));
      } else if (PARENT_ID_ERROR.equals(parentId)) {
        return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE));
      }
      // Includes the case of PARENT_ID_NO_CHILDREN.
      return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.of(), params));
    }

    @Override
    @SuppressWarnings("FutureReturnValueIgnored")
    public ListenableFuture<LibraryResult<Void>> onSearch(
        MediaLibrarySession session,
        ControllerInfo browser,
        String query,
        @Nullable LibraryParams params) {
      assertLibraryParams(params);
      if (SEARCH_QUERY.equals(query)) {
        MockMediaLibraryService.this.session.notifySearchResultChanged(
            browser, query, SEARCH_RESULT_COUNT, params);
      } else if (SEARCH_QUERY_LONG_LIST.equals(query)) {
        MockMediaLibraryService.this.session.notifySearchResultChanged(
            browser, query, LONG_LIST_COUNT, params);
      } else if (SEARCH_QUERY_TAKES_TIME.equals(query)) {
        // Searching takes some time. Notify after 5 seconds.
        Executors.newSingleThreadScheduledExecutor()
            .schedule(
                new Runnable() {
                  @Override
                  public void run() {
                    MockMediaLibraryService.this.session.notifySearchResultChanged(
                        browser, query, SEARCH_RESULT_COUNT, params);
                  }
                },
                SEARCH_TIME_IN_MS,
                MILLISECONDS);
      } else {
        // SEARCH_QUERY_EMPTY_RESULT and SEARCH_QUERY_ERROR will be handled here.
        MockMediaLibraryService.this.session.notifySearchResultChanged(browser, query, 0, params);
      }
      return Futures.immediateFuture(LibraryResult.ofVoid(params));
    }

    @Override
    public ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> onGetSearchResult(
        MediaLibrarySession session,
        ControllerInfo browser,
        String query,
        int page,
        int pageSize,
        @Nullable LibraryParams params) {
      assertLibraryParams(params);
      if (SEARCH_QUERY.equals(query)) {
        return Futures.immediateFuture(
            LibraryResult.ofItemList(getPaginatedResult(SEARCH_RESULT, page, pageSize), params));
      } else if (SEARCH_QUERY_LONG_LIST.equals(query)) {
        List<MediaItem> list = new ArrayList<>(LONG_LIST_COUNT);
        for (int i = 0; i < LONG_LIST_COUNT; i++) {
          list.add(createPlayableMediaItem(TestUtils.getMediaIdInFakeTimeline(i)));
        }
        return Futures.immediateFuture(LibraryResult.ofItemList(list, params));
      } else if (SEARCH_QUERY_EMPTY_RESULT.equals(query)) {
        return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.of(), params));
      } else {
        // SEARCH_QUERY_ERROR will be handled here.
        return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE));
      }
    }

    @Override
    public ListenableFuture<LibraryResult<Void>> onSubscribe(
        MediaLibrarySession session,
        ControllerInfo browser,
        String parentId,
        LibraryParams params) {
      assertLibraryParams(params);
      String unsubscribedId = "unsubscribedId";
      switch (parentId) {
        case SUBSCRIBE_ID_NOTIFY_CHILDREN_CHANGED_TO_ALL:
          MockMediaLibraryService.this.session.notifyChildrenChanged(
              parentId, NOTIFY_CHILDREN_CHANGED_ITEM_COUNT, NOTIFY_CHILDREN_CHANGED_PARAMS);
          return Futures.immediateFuture(LibraryResult.ofVoid(params));
        case SUBSCRIBE_ID_NOTIFY_CHILDREN_CHANGED_TO_ONE:
          MockMediaLibraryService.this.session.notifyChildrenChanged(
              MediaTestUtils.getTestControllerInfo(MockMediaLibraryService.this.session),
              parentId,
              NOTIFY_CHILDREN_CHANGED_ITEM_COUNT,
              NOTIFY_CHILDREN_CHANGED_PARAMS);
          return Futures.immediateFuture(LibraryResult.ofVoid(params));
        case SUBSCRIBE_ID_NOTIFY_CHILDREN_CHANGED_TO_ALL_WITH_NON_SUBSCRIBED_ID:
          MockMediaLibraryService.this.session.notifyChildrenChanged(
              unsubscribedId, NOTIFY_CHILDREN_CHANGED_ITEM_COUNT, NOTIFY_CHILDREN_CHANGED_PARAMS);
          return Futures.immediateFuture(LibraryResult.ofVoid(params));
        case SUBSCRIBE_ID_NOTIFY_CHILDREN_CHANGED_TO_ONE_WITH_NON_SUBSCRIBED_ID:
          MockMediaLibraryService.this.session.notifyChildrenChanged(
              MediaTestUtils.getTestControllerInfo(MockMediaLibraryService.this.session),
              unsubscribedId,
              NOTIFY_CHILDREN_CHANGED_ITEM_COUNT,
              NOTIFY_CHILDREN_CHANGED_PARAMS);
          return Futures.immediateFuture(LibraryResult.ofVoid(params));
        default: // fall out
      }
      return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE));
    }

    @Override
    public ListenableFuture<SessionResult> onCustomCommand(
        MediaSession session,
        ControllerInfo controller,
        SessionCommand sessionCommand,
        Bundle args) {
      switch (sessionCommand.customAction) {
        case CUSTOM_ACTION:
          return Futures.immediateFuture(
              new SessionResult(SessionResult.RESULT_SUCCESS, CUSTOM_ACTION_EXTRAS));
        case CUSTOM_ACTION_ASSERT_PARAMS:
          LibraryParams params =
              BundleableUtil.fromNullableBundle(
                  LibraryParams.CREATOR, args.getBundle(CUSTOM_ACTION_ASSERT_PARAMS));
          setAssertLibraryParams(params);
          return Futures.immediateFuture(new SessionResult(SessionResult.RESULT_SUCCESS));
        default: // fall out
      }
      return Futures.immediateFuture(new SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE));
    }

    private void assertLibraryParams(@Nullable LibraryParams params) {
      synchronized (MockMediaLibraryService.class) {
        if (assertLibraryParams) {
          assertLibraryParamsEquals(expectedParams, params);
        }
      }
    }
  }

  private List<MediaItem> getPaginatedResult(List<String> items, int page, int pageSize) {
    if (items == null) {
      return null;
    } else if (items.size() == 0) {
      return new ArrayList<>();
    }

    int totalItemCount = items.size();
    int fromIndex = page * pageSize;
    int toIndex = Math.min((page + 1) * pageSize, totalItemCount);

    List<String> paginatedMediaIdList = new ArrayList<>();
    try {
      // The case of (fromIndex >= totalItemCount) will throw exception below.
      paginatedMediaIdList = items.subList(fromIndex, toIndex);
    } catch (IndexOutOfBoundsException | IllegalArgumentException e) {
      Log.d(
          TAG,
          "Result is empty for given pagination arguments: totalItemCount="
              + totalItemCount
              + ", page="
              + page
              + ", pageSize="
              + pageSize,
          e);
    }

    // Create a list of MediaItem from the list of media IDs.
    List<MediaItem> result = new ArrayList<>();
    for (int i = 0; i < paginatedMediaIdList.size(); i++) {
      result.add(createPlayableMediaItem(paginatedMediaIdList.get(i)));
    }
    return result;
  }

  private static MediaItem createBrowsableMediaItem(String mediaId) {
    MediaMetadata mediaMetadata =
        new MediaMetadata.Builder()
            .setFolderType(MediaMetadata.FOLDER_TYPE_MIXED)
            .setIsPlayable(false)
            .build();
    return new MediaItem.Builder().setMediaId(mediaId).setMediaMetadata(mediaMetadata).build();
  }

  private static MediaItem createPlayableMediaItem(String mediaId) {
    MediaMetadata mediaMetadata =
        new MediaMetadata.Builder()
            .setFolderType(MediaMetadata.FOLDER_TYPE_NONE)
            .setIsPlayable(true)
            .build();
    return new MediaItem.Builder().setMediaId(mediaId).setMediaMetadata(mediaMetadata).build();
  }

  private static MediaItem createMediaItemWithMetadata(String mediaId) {
    MediaMetadata mediaMetadata = MediaTestUtils.createMediaMetadata();
    return new MediaItem.Builder().setMediaId(mediaId).setMediaMetadata(mediaMetadata).build();
  }
}
