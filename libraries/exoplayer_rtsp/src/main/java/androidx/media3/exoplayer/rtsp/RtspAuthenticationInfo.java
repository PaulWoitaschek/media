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
package androidx.media3.exoplayer.rtsp;

import android.net.Uri;
import android.util.Base64;
import androidx.annotation.IntDef;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.rtsp.RtspMessageUtil.RtspAuthUserInfo;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Wraps RTSP authentication information. */
@UnstableApi
/* package */ final class RtspAuthenticationInfo {

  /** The supported authentication methods. */
  @Retention(RetentionPolicy.SOURCE)
  @IntDef({BASIC, DIGEST})
  @interface AuthenticationMechanism {}

  /** HTTP basic authentication (RFC2068 Section 11.1). */
  public static final int BASIC = 1;
  /** HTTP digest authentication (RFC2069). */
  public static final int DIGEST = 2;

  private static final String DIGEST_FORMAT =
      "Digest username=\"%s\", realm=\"%s\", nonce=\"%s\", uri=\"%s\", response=\"%s\"";
  private static final String DIGEST_FORMAT_WITH_OPAQUE =
      "Digest username=\"%s\", realm=\"%s\", nonce=\"%s\", uri=\"%s\", response=\"%s\","
          + " opaque=\"%s\"";

  private static final String ALGORITHM = "MD5";

  /** The authentication mechanism. */
  @AuthenticationMechanism public final int authenticationMechanism;
  /** The authentication realm. */
  public final String realm;
  /** The nonce used in digest authentication; empty if using {@link #BASIC} authentication. */
  public final String nonce;
  /** The opaque used in digest authentication; empty if using {@link #BASIC} authentication. */
  public final String opaque;

  /**
   * Creates a new instance.
   *
   * @param authenticationMechanism The authentication mechanism, as defined by {@link
   *     AuthenticationMechanism}.
   * @param realm The authentication realm.
   * @param nonce The nonce in digest authentication; empty if using {@link #BASIC} authentication.
   * @param opaque The opaque in digest authentication; empty if using {@link #BASIC}
   *     authentication.
   */
  public RtspAuthenticationInfo(
      @AuthenticationMechanism int authenticationMechanism,
      String realm,
      String nonce,
      String opaque) {
    this.authenticationMechanism = authenticationMechanism;
    this.realm = realm;
    this.nonce = nonce;
    this.opaque = opaque;
  }

  /**
   * Gets the string value for {@link RtspHeaders#AUTHORIZATION} header.
   *
   * @param authUserInfo The {@link RtspAuthUserInfo} for authentication.
   * @param uri The request {@link Uri}.
   * @param requestMethod The request method, defined in {@link RtspRequest.Method}.
   * @return The string value for {@link RtspHeaders#AUTHORIZATION} header.
   * @throws ParserException If the MD5 algorithm is not supported by {@link MessageDigest}.
   */
  public String getAuthorizationHeaderValue(
      RtspAuthUserInfo authUserInfo, Uri uri, @RtspRequest.Method int requestMethod)
      throws ParserException {
    switch (authenticationMechanism) {
      case BASIC:
        return getBasicAuthorizationHeaderValue(authUserInfo);
      case DIGEST:
        return getDigestAuthorizationHeaderValue(authUserInfo, uri, requestMethod);
      default:
        throw ParserException.createForManifestWithUnsupportedFeature(
            /* message= */ null, new UnsupportedOperationException());
    }
  }

  private String getBasicAuthorizationHeaderValue(RtspAuthUserInfo authUserInfo) {
    return Base64.encodeToString(
        RtspMessageUtil.getStringBytes(authUserInfo.username + ":" + authUserInfo.password),
        Base64.DEFAULT);
  }

  private String getDigestAuthorizationHeaderValue(
      RtspAuthUserInfo authUserInfo, Uri uri, @RtspRequest.Method int requestMethod)
      throws ParserException {
    try {
      MessageDigest md = MessageDigest.getInstance(ALGORITHM);
      String methodName = RtspMessageUtil.toMethodString(requestMethod);
      // From RFC2069 Section 2.1.2:
      // response-digest = H( H(A1) ":" unquoted nonce-value ":" H(A2) )
      //     A1          = unquoted username-value ":" unquoted realm-value ":" password
      //     A2          = Method ":" request-uri
      //    H(x)         = MD5(x)

      String hashA1 =
          Util.toHexString(
              md.digest(
                  RtspMessageUtil.getStringBytes(
                      authUserInfo.username + ":" + realm + ":" + authUserInfo.password)));
      String hashA2 =
          Util.toHexString(md.digest(RtspMessageUtil.getStringBytes(methodName + ":" + uri)));
      String response =
          Util.toHexString(
              md.digest(RtspMessageUtil.getStringBytes(hashA1 + ":" + nonce + ":" + hashA2)));

      if (opaque.isEmpty()) {
        return Util.formatInvariant(
            DIGEST_FORMAT, authUserInfo.username, realm, nonce, uri, response);
      } else {
        return Util.formatInvariant(
            DIGEST_FORMAT_WITH_OPAQUE, authUserInfo.username, realm, nonce, uri, response, opaque);
      }
    } catch (NoSuchAlgorithmException e) {
      throw ParserException.createForManifestWithUnsupportedFeature(/* message= */ null, e);
    }
  }
}
