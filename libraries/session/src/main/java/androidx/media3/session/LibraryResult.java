/*
 * Copyright 2019 The Android Open Source Project
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

import static androidx.media3.common.util.Assertions.checkArgument;
import static androidx.media3.common.util.Assertions.checkNotEmpty;
import static androidx.media3.common.util.Assertions.checkState;
import static java.lang.annotation.ElementType.TYPE_USE;

import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.MediaSessionCompat;
import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.core.app.BundleCompat;
import androidx.media3.common.BundleListRetriever;
import androidx.media3.common.Bundleable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.util.BundleableUtil;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaLibraryService.LibraryParams;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;

/**
 * A result to be used with {@link ListenableFuture} for asynchronous calls between {@link
 * MediaLibraryService.MediaLibrarySession} and {@link MediaBrowser}.
 */
public final class LibraryResult<V> implements Bundleable {

  /** Result codes. */
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef({
    RESULT_SUCCESS,
    RESULT_ERROR_UNKNOWN,
    RESULT_ERROR_INVALID_STATE,
    RESULT_ERROR_BAD_VALUE,
    RESULT_ERROR_PERMISSION_DENIED,
    RESULT_ERROR_IO,
    RESULT_INFO_SKIPPED,
    RESULT_ERROR_SESSION_DISCONNECTED,
    RESULT_ERROR_NOT_SUPPORTED,
    RESULT_ERROR_SESSION_AUTHENTICATION_EXPIRED,
    RESULT_ERROR_SESSION_PREMIUM_ACCOUNT_REQUIRED,
    RESULT_ERROR_SESSION_CONCURRENT_STREAM_LIMIT,
    RESULT_ERROR_SESSION_PARENTAL_CONTROL_RESTRICTED,
    RESULT_ERROR_SESSION_NOT_AVAILABLE_IN_REGION,
    RESULT_ERROR_SESSION_SKIP_LIMIT_REACHED,
    RESULT_ERROR_SESSION_SETUP_REQUIRED
  })
  public @interface Code {}

  /**
   * Result code representing that the command is successfully completed.
   *
   * <p>Interoperability: This code is also used to tell that the command was successfully sent, but
   * the result is unknown when connected with {@link MediaSessionCompat} or {@link
   * MediaControllerCompat}.
   */
  public static final int RESULT_SUCCESS = 0;

  /** Result code representing that the command is ended with an unknown error. */
  public static final int RESULT_ERROR_UNKNOWN = -1;

  /**
   * Result code representing that the command cannot be completed because the current state is not
   * valid for the command.
   */
  public static final int RESULT_ERROR_INVALID_STATE = -2;

  /** Result code representing that an argument is illegal. */
  public static final int RESULT_ERROR_BAD_VALUE = -3;

  /** Result code representing that the command is not allowed. */
  public static final int RESULT_ERROR_PERMISSION_DENIED = -4;

  /** Result code representing that a file or network related error happened. */
  public static final int RESULT_ERROR_IO = -5;

  /** Result code representing that the command is not supported. */
  public static final int RESULT_ERROR_NOT_SUPPORTED = -6;

  /** Result code representing that the command is skipped. */
  public static final int RESULT_INFO_SKIPPED = 1;

  /** Result code representing that the session and controller were disconnected. */
  public static final int RESULT_ERROR_SESSION_DISCONNECTED = -100;

  /** Result code representing that the authentication has expired. */
  public static final int RESULT_ERROR_SESSION_AUTHENTICATION_EXPIRED = -102;

  /** Result code representing that a premium account is required. */
  public static final int RESULT_ERROR_SESSION_PREMIUM_ACCOUNT_REQUIRED = -103;

  /** Result code representing that too many concurrent streams are detected. */
  public static final int RESULT_ERROR_SESSION_CONCURRENT_STREAM_LIMIT = -104;

  /** Result code representing that the content is blocked due to parental controls. */
  public static final int RESULT_ERROR_SESSION_PARENTAL_CONTROL_RESTRICTED = -105;

  /** Result code representing that the content is blocked due to being regionally unavailable. */
  public static final int RESULT_ERROR_SESSION_NOT_AVAILABLE_IN_REGION = -106;

  /**
   * Result code representing that the application cannot skip any more because the skip limit is
   * reached.
   */
  public static final int RESULT_ERROR_SESSION_SKIP_LIMIT_REACHED = -107;

  /** Result code representing that the session needs user's manual intervention. */
  public static final int RESULT_ERROR_SESSION_SETUP_REQUIRED = -108;

  /** The {@link Code} of this result. */
  public final @Code int resultCode;

  /**
   * The completion time of the command in milliseconds. It's the same as {@link
   * SystemClock#elapsedRealtime()} when the command is completed.
   */
  public final long completionTimeMs;

  /**
   * The value of this result. Will be {@code null} if {@link #resultCode} is not {@link
   * #RESULT_SUCCESS}.
   */
  @Nullable public final V value;

  private final @ValueType int valueType;

  /** The optional parameters. */
  @Nullable public final MediaLibraryService.LibraryParams params;

  /** Creates an instance with {@link #resultCode}{@code ==}{@link #RESULT_SUCCESS}. */
  public static LibraryResult<Void> ofVoid() {
    return new LibraryResult<>(
        RESULT_SUCCESS,
        SystemClock.elapsedRealtime(),
        /* params= */ null,
        /* value= */ null,
        VALUE_TYPE_VOID);
  }

  /**
   * Creates an instance with {@link #resultCode}{@code ==}{@link #RESULT_SUCCESS} and optional
   * {@link LibraryParams params}.
   */
  public static LibraryResult<Void> ofVoid(@Nullable LibraryParams params) {
    return new LibraryResult<>(
        RESULT_SUCCESS, SystemClock.elapsedRealtime(), params, /* value= */ null, VALUE_TYPE_VOID);
  }

  /**
   * Creates an instance with a media item and {@link #resultCode}{@code ==}{@link #RESULT_SUCCESS}.
   *
   * <p>The {@link MediaItem#mediaMetadata} must specify {@link MediaMetadata#folderType} and {@link
   * MediaMetadata#isPlayable} fields.
   *
   * @param item The media item.
   * @param params The optional parameters to describe the media item.
   */
  public static LibraryResult<MediaItem> ofItem(MediaItem item, @Nullable LibraryParams params) {
    verifyMediaItem(item);
    return new LibraryResult<>(
        RESULT_SUCCESS, SystemClock.elapsedRealtime(), params, item, VALUE_TYPE_ITEM);
  }

  /**
   * Creates an instance with a list of media items and {@link #resultCode}{@code ==}{@link
   * #RESULT_SUCCESS}.
   *
   * <p>The {@link MediaItem#mediaMetadata} of each item in the list must specify {@link
   * MediaMetadata#folderType} and {@link MediaMetadata#isPlayable} fields.
   *
   * @param items The list of media items.
   * @param params The optional parameters to describe the list of media items.
   */
  public static LibraryResult<ImmutableList<MediaItem>> ofItemList(
      List<MediaItem> items, @Nullable LibraryParams params) {
    for (MediaItem item : items) {
      verifyMediaItem(item);
    }
    return new LibraryResult<>(
        RESULT_SUCCESS,
        SystemClock.elapsedRealtime(),
        params,
        ImmutableList.copyOf(items),
        VALUE_TYPE_ITEM_LIST);
  }

  /**
   * Creates an instance with an unsuccessful {@link Code result code}.
   *
   * <p>{@code errorCode} must not be {@link #RESULT_SUCCESS}.
   *
   * @param errorCode The error code.
   */
  public static <V> LibraryResult<V> ofError(@Code int errorCode) {
    checkArgument(errorCode != RESULT_SUCCESS);
    return new LibraryResult<>(
        errorCode,
        SystemClock.elapsedRealtime(),
        /* params= */ null,
        /* value= */ null,
        VALUE_TYPE_ERROR);
  }

  private LibraryResult(
      @Code int resultCode,
      long completionTimeMs,
      @Nullable LibraryParams params,
      @Nullable V value,
      @ValueType int valueType) {
    this.resultCode = resultCode;
    this.completionTimeMs = completionTimeMs;
    this.params = params;
    this.value = value;
    this.valueType = valueType;
  }

  private static void verifyMediaItem(MediaItem item) {
    checkNotEmpty(item.mediaId, "mediaId must not be empty");
    checkArgument(item.mediaMetadata.folderType != null, "mediaMetadata must specify folderType");
    checkArgument(item.mediaMetadata.isPlayable != null, "mediaMetadata must specify isPlayable");
  }

  // Bundleable implementation.

  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @IntDef({
    FIELD_RESULT_CODE,
    FIELD_COMPLETION_TIME_MS,
    FIELD_PARAMS,
    FIELD_VALUE,
    FIELD_VALUE_TYPE
  })
  private @interface FieldNumber {}

  private static final int FIELD_RESULT_CODE = 0;
  private static final int FIELD_COMPLETION_TIME_MS = 1;
  private static final int FIELD_PARAMS = 2;
  private static final int FIELD_VALUE = 3;
  private static final int FIELD_VALUE_TYPE = 4;

  @UnstableApi
  @Override
  public Bundle toBundle() {
    Bundle bundle = new Bundle();
    bundle.putInt(keyForField(FIELD_RESULT_CODE), resultCode);
    bundle.putLong(keyForField(FIELD_COMPLETION_TIME_MS), completionTimeMs);
    bundle.putBundle(keyForField(FIELD_PARAMS), BundleableUtil.toNullableBundle(params));
    bundle.putInt(keyForField(FIELD_VALUE_TYPE), valueType);

    if (value == null) {
      return bundle;
    }
    switch (valueType) {
      case VALUE_TYPE_ITEM:
        bundle.putBundle(
            keyForField(FIELD_VALUE), BundleableUtil.toNullableBundle((MediaItem) value));
        break;
      case VALUE_TYPE_ITEM_LIST:
        BundleCompat.putBinder(
            bundle,
            keyForField(FIELD_VALUE),
            new BundleListRetriever(BundleableUtil.toBundleList((ImmutableList<MediaItem>) value)));
        break;
      case VALUE_TYPE_VOID:
      case VALUE_TYPE_ERROR:
        // value must be null for both these types, so we should have returned above.
        throw new IllegalStateException();
    }
    return bundle;
  }

  /** Object that can restore a {@code LibraryResult<Void>} from a {@link Bundle}. */
  @UnstableApi
  public static final Creator<LibraryResult<Void>> VOID_CREATOR = LibraryResult::fromVoidBundle;

  /** Object that can restore a {@code LibraryResult<MediaItem>} from a {@link Bundle}. */
  @UnstableApi
  public static final Creator<LibraryResult<MediaItem>> ITEM_CREATOR =
      LibraryResult::fromItemBundle;

  /**
   * Object that can restore a {@code LibraryResult<ImmutableList<MediaItem>} from a {@link Bundle}.
   */
  @UnstableApi
  public static final Creator<LibraryResult<ImmutableList<MediaItem>>> ITEM_LIST_CREATOR =
      LibraryResult::fromItemListBundle;

  /**
   * Object that can restore a {@code LibraryResult} with unknown value type from a {@link Bundle}.
   */
  @UnstableApi
  public static final Creator<LibraryResult<?>> UNKNOWN_TYPE_CREATOR =
      LibraryResult::fromUnknownBundle;

  // fromBundle will throw if the bundle doesn't have the right value type.
  @SuppressWarnings("unchecked")
  private static LibraryResult<Void> fromVoidBundle(Bundle bundle) {
    return (LibraryResult<Void>) fromUnknownBundle(bundle);
  }

  // fromBundle will throw if the bundle doesn't have the right value type.
  @SuppressWarnings("unchecked")
  private static LibraryResult<MediaItem> fromItemBundle(Bundle bundle) {
    return (LibraryResult<MediaItem>) fromBundle(bundle, VALUE_TYPE_ITEM);
  }

  // fromBundle will throw if the bundle doesn't have the right value type.
  @SuppressWarnings("unchecked")
  private static LibraryResult<ImmutableList<MediaItem>> fromItemListBundle(Bundle bundle) {
    return (LibraryResult<ImmutableList<MediaItem>>) fromBundle(bundle, VALUE_TYPE_ITEM_LIST);
  }

  private static LibraryResult<?> fromUnknownBundle(Bundle bundle) {
    return fromBundle(bundle, /* expectedType= */ null);
  }

  /**
   * Constructs a new instance from {@code bundle}.
   *
   * @throws IllegalStateException if {@code expectedType} is non-null and doesn't match the value
   *     type read from {@code bundle}.
   */
  private static LibraryResult<?> fromBundle(
      Bundle bundle, @Nullable @ValueType Integer expectedType) {
    int resultCode =
        bundle.getInt(keyForField(FIELD_RESULT_CODE), /* defaultValue= */ RESULT_SUCCESS);
    long completionTimeMs =
        bundle.getLong(
            keyForField(FIELD_COMPLETION_TIME_MS),
            /* defaultValue= */ SystemClock.elapsedRealtime());
    @Nullable
    MediaLibraryService.LibraryParams params =
        BundleableUtil.fromNullableBundle(
            MediaLibraryService.LibraryParams.CREATOR, bundle.getBundle(keyForField(FIELD_PARAMS)));
    @ValueType int valueType = bundle.getInt(keyForField(FIELD_VALUE_TYPE));
    @Nullable Object value;
    switch (valueType) {
      case VALUE_TYPE_ITEM:
        checkState(expectedType == null || expectedType == VALUE_TYPE_ITEM);
        value =
            BundleableUtil.fromNullableBundle(
                MediaItem.CREATOR, bundle.getBundle(keyForField(FIELD_VALUE)));
        break;
      case VALUE_TYPE_ITEM_LIST:
        checkState(expectedType == null || expectedType == VALUE_TYPE_ITEM_LIST);
        @Nullable IBinder valueRetriever = BundleCompat.getBinder(bundle, keyForField(FIELD_VALUE));
        value =
            valueRetriever == null
                ? null
                : BundleableUtil.fromBundleList(
                    MediaItem.CREATOR, BundleListRetriever.getList(valueRetriever));
        break;
      case VALUE_TYPE_VOID:
      case VALUE_TYPE_ERROR:
        value = null;
        break;
      default:
        throw new IllegalStateException();
    }

    return new LibraryResult<>(resultCode, completionTimeMs, params, value, VALUE_TYPE_ITEM_LIST);
  }

  private static String keyForField(@FieldNumber int field) {
    return Integer.toString(field, Character.MAX_RADIX);
  }

  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef({VALUE_TYPE_VOID, VALUE_TYPE_ITEM, VALUE_TYPE_ITEM_LIST, VALUE_TYPE_ERROR})
  private @interface ValueType {}

  private static final int VALUE_TYPE_VOID = 1;
  private static final int VALUE_TYPE_ITEM = 2;
  private static final int VALUE_TYPE_ITEM_LIST = 3;
  /** The value type isn't known because the result is carrying an error. */
  private static final int VALUE_TYPE_ERROR = 4;
}
