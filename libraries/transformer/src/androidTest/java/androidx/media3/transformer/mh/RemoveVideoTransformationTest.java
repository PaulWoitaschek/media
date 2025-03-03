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
package androidx.media3.transformer.mh;

import static androidx.media3.transformer.mh.AndroidTestUtil.MP4_ASSET_URI_STRING;
import static androidx.media3.transformer.mh.AndroidTestUtil.runTransformer;

import android.content.Context;
import androidx.media3.transformer.Transformer;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

/** {@link Transformer} instrumentation test for removing video. */
@RunWith(AndroidJUnit4.class)
public class RemoveVideoTransformationTest {
  @Test
  public void removeVideoTransform() throws Exception {
    Context context = ApplicationProvider.getApplicationContext();
    Transformer transformer = new Transformer.Builder(context).setRemoveVideo(true).build();
    runTransformer(
        context,
        /* testId= */ "removeVideoTransform",
        transformer,
        MP4_ASSET_URI_STRING,
        /* timeoutSeconds= */ 120);
  }
}
