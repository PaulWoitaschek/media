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

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.view.Surface;
import android.view.SurfaceView;
import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.media3.common.util.GlUtil;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

/** FrameEditor applies changes to individual video frames. */
/* package */ final class FrameEditor {

  static {
    GlUtil.glAssertionsEnabled = true;
  }

  /**
   * Returns a new {@code FrameEditor} for applying changes to individual frames.
   *
   * @param context A {@link Context}.
   * @param outputWidth The output width in pixels.
   * @param outputHeight The output height in pixels.
   * @param transformationMatrix The transformation matrix to apply to each frame.
   * @param outputSurface The {@link Surface}.
   * @param debugViewProvider Provider for optional debug views to show intermediate output.
   * @return A configured {@code FrameEditor}.
   */
  public static FrameEditor create(
      Context context,
      int outputWidth,
      int outputHeight,
      Matrix transformationMatrix,
      Surface outputSurface,
      Transformer.DebugViewProvider debugViewProvider) {
    EGLDisplay eglDisplay = GlUtil.createEglDisplay();
    EGLContext eglContext = GlUtil.createEglContext(eglDisplay);
    EGLSurface eglSurface = GlUtil.getEglSurface(eglDisplay, outputSurface);
    GlUtil.focusSurface(eglDisplay, eglContext, eglSurface, outputWidth, outputHeight);
    int textureId = GlUtil.createExternalTexture();
    GlUtil.Program glProgram;
    try {
      // TODO(internal b/205002913): check the loaded program is consistent with the attributes
      // and uniforms expected in the code.
      glProgram = new GlUtil.Program(context, VERTEX_SHADER_FILE_PATH, FRAGMENT_SHADER_FILE_PATH);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }

    glProgram.setBufferAttribute(
        "a_position",
        new float[] {
          -1.0f, -1.0f, 0.0f, 1.0f,
          1.0f, -1.0f, 0.0f, 1.0f,
          -1.0f, 1.0f, 0.0f, 1.0f,
          1.0f, 1.0f, 0.0f, 1.0f,
        },
        /* size= */ 4);
    glProgram.setBufferAttribute(
        "a_texcoord",
        new float[] {
          0.0f, 0.0f, 0.0f, 1.0f,
          1.0f, 0.0f, 0.0f, 1.0f,
          0.0f, 1.0f, 0.0f, 1.0f,
          1.0f, 1.0f, 0.0f, 1.0f,
        },
        /* size= */ 4);
    glProgram.setSamplerTexIdUniform("tex_sampler", textureId, /* unit= */ 0);

    float[] transformationMatrixArray = getGlMatrixArray(transformationMatrix);
    glProgram.setFloatsUniform("transformation_matrix", transformationMatrixArray);

    @Nullable
    SurfaceView debugSurfaceView =
        debugViewProvider.getDebugPreviewSurfaceView(outputWidth, outputHeight);
    @Nullable EGLSurface debugPreviewEglSurface;
    int debugPreviewWidth;
    int debugPreviewHeight;
    if (debugSurfaceView != null) {
      debugPreviewEglSurface =
          GlUtil.getEglSurface(eglDisplay, checkNotNull(debugSurfaceView.getHolder()));
      debugPreviewWidth = debugSurfaceView.getWidth();
      debugPreviewHeight = debugSurfaceView.getHeight();
    } else {
      debugPreviewEglSurface = null;
      debugPreviewWidth = Format.NO_VALUE;
      debugPreviewHeight = Format.NO_VALUE;
    }
    return new FrameEditor(
        eglDisplay,
        eglContext,
        eglSurface,
        textureId,
        glProgram,
        outputWidth,
        outputHeight,
        debugPreviewEglSurface,
        debugPreviewWidth,
        debugPreviewHeight);
  }

  /**
   * Returns a 4x4, column-major Matrix float array, from an input {@link Matrix}. This is useful
   * for converting to the 4x4 column-major format commonly used in OpenGL.
   */
  private static float[] getGlMatrixArray(Matrix matrix) {
    float[] matrix3x3Array = new float[9];
    matrix.getValues(matrix3x3Array);
    float[] matrix4x4Array = getMatrix4x4Array(matrix3x3Array);

    // Transpose from row-major to column-major representations.
    float[] transposedMatrix4x4Array = new float[16];
    android.opengl.Matrix.transposeM(
        transposedMatrix4x4Array, /* mTransOffset= */ 0, matrix4x4Array, /* mOffset= */ 0);

    return transposedMatrix4x4Array;
  }

  /**
   * Returns a 4x4 matrix array containing the 3x3 matrix array's contents.
   *
   * <p>The 3x3 matrix array is expected to be in 2 dimensions, and the 4x4 matrix array is expected
   * to be in 3 dimensions. The output will have the third row/column's values be an identity
   * matrix's values, so that vertex transformations using this matrix will not affect the z axis.
   * <br>
   * Input format: [a, b, c, d, e, f, g, h, i] <br>
   * Output format: [a, b, 0, c, d, e, 0, f, 0, 0, 1, 0, g, h, 0, i]
   */
  private static float[] getMatrix4x4Array(float[] matrix3x3Array) {
    float[] matrix4x4Array = new float[16];
    matrix4x4Array[10] = 1;
    for (int inputRow = 0; inputRow < 3; inputRow++) {
      for (int inputColumn = 0; inputColumn < 3; inputColumn++) {
        int outputRow = (inputRow == 2) ? 3 : inputRow;
        int outputColumn = (inputColumn == 2) ? 3 : inputColumn;
        matrix4x4Array[outputRow * 4 + outputColumn] = matrix3x3Array[inputRow * 3 + inputColumn];
      }
    }
    return matrix4x4Array;
  }

  // Predefined shader values.
  private static final String VERTEX_SHADER_FILE_PATH = "shaders/vertex_shader.glsl";
  private static final String FRAGMENT_SHADER_FILE_PATH = "shaders/fragment_shader.glsl";

  private final float[] textureTransformMatrix;
  private final EGLDisplay eglDisplay;
  private final EGLContext eglContext;
  private final EGLSurface eglSurface;
  private final int textureId;
  private final AtomicInteger pendingInputFrameCount;
  private final SurfaceTexture inputSurfaceTexture;
  private final Surface inputSurface;
  private final GlUtil.Program glProgram;
  private final int outputWidth;
  private final int outputHeight;
  @Nullable private final EGLSurface debugPreviewEglSurface;
  private final int debugPreviewWidth;
  private final int debugPreviewHeight;

  private FrameEditor(
      EGLDisplay eglDisplay,
      EGLContext eglContext,
      EGLSurface eglSurface,
      int textureId,
      GlUtil.Program glProgram,
      int outputWidth,
      int outputHeight,
      @Nullable EGLSurface debugPreviewEglSurface,
      int debugPreviewWidth,
      int debugPreviewHeight) {
    this.eglDisplay = eglDisplay;
    this.eglContext = eglContext;
    this.eglSurface = eglSurface;
    this.textureId = textureId;
    this.glProgram = glProgram;
    this.pendingInputFrameCount = new AtomicInteger();
    this.outputWidth = outputWidth;
    this.outputHeight = outputHeight;
    this.debugPreviewEglSurface = debugPreviewEglSurface;
    this.debugPreviewWidth = debugPreviewWidth;
    this.debugPreviewHeight = debugPreviewHeight;
    textureTransformMatrix = new float[16];
    inputSurfaceTexture = new SurfaceTexture(textureId);
    inputSurfaceTexture.setOnFrameAvailableListener(
        surfaceTexture -> pendingInputFrameCount.incrementAndGet());
    inputSurface = new Surface(inputSurfaceTexture);
  }

  /** Returns the input {@link Surface}. */
  public Surface getInputSurface() {
    return inputSurface;
  }

  /**
   * Returns whether there is pending input data that can be processed by calling {@link
   * #processData()}.
   */
  public boolean hasInputData() {
    return pendingInputFrameCount.get() > 0;
  }

  /** Processes pending input frame. */
  public void processData() {
    inputSurfaceTexture.updateTexImage();
    inputSurfaceTexture.getTransformMatrix(textureTransformMatrix);
    glProgram.setFloatsUniform("tex_transform", textureTransformMatrix);
    glProgram.bindAttributesAndUniforms();

    focusAndDrawQuad(eglSurface, outputWidth, outputHeight);
    long surfaceTextureTimestampNs = inputSurfaceTexture.getTimestamp();
    EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, surfaceTextureTimestampNs);
    EGL14.eglSwapBuffers(eglDisplay, eglSurface);
    pendingInputFrameCount.decrementAndGet();

    if (debugPreviewEglSurface != null) {
      focusAndDrawQuad(debugPreviewEglSurface, debugPreviewWidth, debugPreviewHeight);
      EGL14.eglSwapBuffers(eglDisplay, debugPreviewEglSurface);
    }
  }

  /** Releases all resources. */
  public void release() {
    glProgram.delete();
    GlUtil.deleteTexture(textureId);
    GlUtil.destroyEglContext(eglDisplay, eglContext);
    inputSurfaceTexture.release();
    inputSurface.release();
  }

  /** Focuses the specified surface with the specified width and height, then draws a quad. */
  private void focusAndDrawQuad(EGLSurface eglSurface, int width, int height) {
    GlUtil.focusSurface(eglDisplay, eglContext, eglSurface, width, height);
    // The four-vertex triangle strip forms a quad.
    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first= */ 0, /* count= */ 4);
  }
}
