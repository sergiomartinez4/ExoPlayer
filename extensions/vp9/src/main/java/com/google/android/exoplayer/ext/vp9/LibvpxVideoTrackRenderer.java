/*
 * Copyright (C) 2014 The Android Open Source Project
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
package com.google.android.exoplayer.ext.vp9;

import com.google.android.exoplayer.ExoPlaybackException;
import com.google.android.exoplayer.ExoPlayer;
import com.google.android.exoplayer.MediaFormat;
import com.google.android.exoplayer.MediaFormatHolder;
import com.google.android.exoplayer.SampleSource;
import com.google.android.exoplayer.SampleSource.SampleSourceReader;
import com.google.android.exoplayer.TrackRenderer;
import com.google.android.exoplayer.ext.vp9.VpxDecoderWrapper.InputBuffer;
import com.google.android.exoplayer.ext.vp9.VpxDecoderWrapper.OutputBuffer;
import com.google.android.exoplayer.util.MimeTypes;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Handler;
import android.os.SystemClock;
import android.view.Surface;

import java.io.IOException;

/**
 * Decodes and renders video using the native VP9 decoder.
 */
public class LibvpxVideoTrackRenderer extends TrackRenderer {

  /**
   * Interface definition for a callback to be notified of {@link LibvpxVideoTrackRenderer} events.
   */
  public interface EventListener {

    /**
     * Invoked to report the number of frames dropped by the renderer. Dropped frames are reported
     * whenever the renderer is stopped having dropped frames, and optionally, whenever the count
     * reaches a specified threshold whilst the renderer is started.
     *
     * @param count The number of dropped frames.
     * @param elapsed The duration in milliseconds over which the frames were dropped. This
     *     duration is timed from when the renderer was started or from when dropped frames were
     *     last reported (whichever was more recent), and not from when the first of the reported
     *     drops occurred.
     */
    void onDroppedFrames(int count, long elapsed);

    /**
     * Invoked each time there's a change in the size of the video being rendered.
     *
     * @param width The video width in pixels.
     * @param height The video height in pixels.
     */
    void onVideoSizeChanged(int width, int height);

    /**
     * Invoked when a frame is rendered to a surface for the first time following that surface
     * having been set as the target for the renderer.
     *
     * @param surface The surface to which a first frame has been rendered.
     */
    void onDrawnToSurface(Surface surface);

    /**
     * Invoked when one of the following happens: libvpx initialization failure, decoder error,
     * renderer error.
     *
     * @param e The corresponding exception.
     */
    void onDecoderError(VpxDecoderException e);

  }

  /**
   * The type of a message that can be passed to an instance of this class via
   * {@link ExoPlayer#sendMessage} or {@link ExoPlayer#blockingSendMessage}. The message object
   * should be the target {@link Surface}, or null.
   */
  public static final int MSG_SET_SURFACE = 1;
  public static final int MSG_SET_VPX_SURFACE_VIEW = 2;

  private final SampleSourceReader source;
  private final boolean scaleToFit;
  private final Handler eventHandler;
  private final EventListener eventListener;
  private final int maxDroppedFrameCountToNotify;
  private final MediaFormatHolder formatHolder;

  private MediaFormat format;
  private VpxDecoderWrapper decoder;
  private InputBuffer inputBuffer;
  private OutputBuffer outputBuffer;

  private Bitmap bitmap;
  private boolean drawnToSurface;
  private boolean renderedFirstFrame;
  private Surface surface;
  private VpxVideoSurfaceView vpxVideoSurfaceView;
  private boolean outputRgb;

  private int trackIndex;
  private boolean inputStreamEnded;
  private boolean outputStreamEnded;
  private boolean sourceIsReady;
  private int previousWidth;
  private int previousHeight;

  private int droppedFrameCount;
  private long droppedFrameAccumulationStartTimeMs;

  /**
   * @param source The upstream source from which the renderer obtains samples.
   * @param scaleToFit Boolean that indicates if video frames should be scaled to fit when
   *     rendering.
   */
  public LibvpxVideoTrackRenderer(SampleSource source, boolean scaleToFit) {
    this(source, scaleToFit, null, null, 0);
  }

  /**
   * @param source The upstream source from which the renderer obtains samples.
   * @param scaleToFit Boolean that indicates if video frames should be scaled to fit when
   *     rendering.
   * @param eventHandler A handler to use when delivering events to {@code eventListener}. May be
   *     null if delivery of events is not required.
   * @param eventListener A listener of events. May be null if delivery of events is not required.
   * @param maxDroppedFrameCountToNotify The maximum number of frames that can be dropped between
   *     invocations of {@link EventListener#onDroppedFrames(int, long)}.
   */
  public LibvpxVideoTrackRenderer(SampleSource source, boolean scaleToFit,
      Handler eventHandler, EventListener eventListener, int maxDroppedFrameCountToNotify) {
    this.source = source.register();
    this.scaleToFit = scaleToFit;
    this.eventHandler = eventHandler;
    this.eventListener = eventListener;
    this.maxDroppedFrameCountToNotify = maxDroppedFrameCountToNotify;
    previousWidth = -1;
    previousHeight = -1;
    formatHolder = new MediaFormatHolder();
  }

  @Override
  protected int doPrepare(long positionUs) throws ExoPlaybackException {
    try {
      boolean sourcePrepared = source.prepare(positionUs);
      if (!sourcePrepared) {
        return TrackRenderer.STATE_UNPREPARED;
      }
    } catch (IOException e) {
      throw new ExoPlaybackException(e);
    }

    for (int i = 0; i < source.getTrackCount(); i++) {
      if (source.getTrackInfo(i).mimeType.equalsIgnoreCase(MimeTypes.VIDEO_VP9)
          || source.getTrackInfo(i).mimeType.equalsIgnoreCase(MimeTypes.VIDEO_WEBM)) {
        trackIndex = i;
        return TrackRenderer.STATE_PREPARED;
      }
    }

    return TrackRenderer.STATE_IGNORE;
  }

  @Override
  protected void doSomeWork(long positionUs, long elapsedRealtimeUs) throws ExoPlaybackException {
    if (outputStreamEnded) {
      return;
    }
    try {
      sourceIsReady = source.continueBuffering(trackIndex, positionUs);
      checkForDiscontinuity(positionUs);
      if (format == null) {
        readFormat(positionUs);
      } else {
        // TODO: Add support for dynamic switching between one type of surface to another.
        // Create the decoder.
        if (decoder == null) {
          decoder = new VpxDecoderWrapper(outputRgb);
          decoder.start();
        }
        processOutputBuffer(positionUs, elapsedRealtimeUs);

        // Queue input buffers.
        while (feedInputBuffer(positionUs)) {}
      }
    } catch (VpxDecoderException e) {
      notifyDecoderError(e);
      throw new ExoPlaybackException(e);
    } catch (IOException e) {
      throw new ExoPlaybackException(e);
    }
  }

  private void processOutputBuffer(long positionUs, long elapsedRealtimeUs)
      throws VpxDecoderException {
    if (outputStreamEnded) {
      return;
    }

    if (outputBuffer == null) {
      outputBuffer = decoder.dequeueOutputBuffer();
      if (outputBuffer == null) {
        return;
      }
    }

    if (outputBuffer.flags == VpxDecoderWrapper.FLAG_END_OF_STREAM) {
      outputStreamEnded = true;
      releaseOutputBuffer();
      return;
    }

    long elapsedSinceStartOfLoop = SystemClock.elapsedRealtime() * 1000 - elapsedRealtimeUs;
    long timeToRenderUs = outputBuffer.timestampUs - positionUs - elapsedSinceStartOfLoop;

    if (timeToRenderUs < -30000 || outputBuffer.timestampUs < positionUs) {
      // Drop frame if we are too late.
      droppedFrameCount++;
      if (droppedFrameCount == maxDroppedFrameCountToNotify) {
        notifyAndResetDroppedFrameCount();
      }
      releaseOutputBuffer();
      return;
    }

    // If we have not renderered any frame so far (either initially or immediately following a
    // seek), render one frame irresepective of the state.
    if (!renderedFirstFrame) {
      renderBuffer();
      renderedFirstFrame = true;
      return;
    }

    // Do nothing if we are not playing or if we are too early to render the next frame.
    if (getState() != TrackRenderer.STATE_STARTED || timeToRenderUs > 30000) {
      return;
    }

    if (timeToRenderUs > 11000) {
      try {
        // Subtracting 10000 rather than 11000 ensures that the sleep time
        // will be at least 1ms.
        Thread.sleep((timeToRenderUs - 10000) / 1000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    renderBuffer();
  }

  private void renderBuffer() throws VpxDecoderException {
    notifyIfVideoSizeChanged(outputBuffer);
    if (outputRgb) {
      renderRgbFrame(outputBuffer, scaleToFit);
    } else {
      vpxVideoSurfaceView.renderFrame(outputBuffer);
    }
    if (!drawnToSurface) {
      drawnToSurface = true;
      notifyDrawnToSurface(surface);
    }
    releaseOutputBuffer();
  }

  private void releaseOutputBuffer() throws VpxDecoderException {
    decoder.releaseOutputBuffer(outputBuffer);
    outputBuffer = null;
  }

  private void renderRgbFrame(OutputBuffer outputBuffer, boolean scale) {
    if (bitmap == null || bitmap.getWidth() != outputBuffer.width
        || bitmap.getHeight() != outputBuffer.height) {
      bitmap = Bitmap.createBitmap(outputBuffer.width, outputBuffer.height, Bitmap.Config.RGB_565);
    }
    bitmap.copyPixelsFromBuffer(outputBuffer.data);
    Canvas canvas = surface.lockCanvas(null);
    if (scale) {
      canvas.scale(((float) canvas.getWidth()) / outputBuffer.width,
          ((float) canvas.getHeight()) / outputBuffer.height);
    }
    canvas.drawBitmap(bitmap, 0, 0, null);
    surface.unlockCanvasAndPost(canvas);
  }

  private boolean feedInputBuffer(long positionUs) throws IOException, VpxDecoderException {
    if (inputStreamEnded) {
      return false;
    }

    if (inputBuffer == null) {
      inputBuffer = decoder.getInputBuffer();
      if (inputBuffer == null) {
        return false;
      }
    }

    int result = source.readData(trackIndex, positionUs, formatHolder, inputBuffer.sampleHolder,
        false);
    if (result == SampleSource.NOTHING_READ) {
      return false;
    }
    if (result == SampleSource.DISCONTINUITY_READ) {
      flushDecoder();
      return true;
    }
    if (result == SampleSource.FORMAT_READ) {
      format = formatHolder.format;
      return true;
    }
    if (result == SampleSource.END_OF_STREAM) {
      inputBuffer.flags = VpxDecoderWrapper.FLAG_END_OF_STREAM;
      decoder.queueInputBuffer(inputBuffer);
      inputBuffer = null;
      inputStreamEnded = true;
      return false;
    }

    inputBuffer.width = format.width;
    inputBuffer.height = format.height;
    decoder.queueInputBuffer(inputBuffer);
    inputBuffer = null;
    return true;
  }

  private void checkForDiscontinuity(long positionUs) throws IOException {
    if (decoder == null) {
      return;
    }
    int result = source.readData(trackIndex, positionUs, formatHolder, null, true);
    if (result == SampleSource.DISCONTINUITY_READ) {
      flushDecoder();
    }
  }

  private void flushDecoder() {
    inputBuffer = null;
    outputBuffer = null;
    decoder.flush();
  }

  @Override
  protected boolean isEnded() {
    return outputStreamEnded;
  }

  @Override
  protected boolean isReady() {
    return format != null && sourceIsReady;
  }

  @Override
  protected long getDurationUs() {
    return source.getTrackInfo(trackIndex).durationUs;
  }

  @Override
  protected long getBufferedPositionUs() {
    return source.getBufferedPositionUs();
  }

  @Override
  protected void seekTo(long positionUs) throws ExoPlaybackException {
    source.seekToUs(positionUs);
    seekToInternal();
  }

  @Override
  protected void onEnabled(long positionUs, boolean joining) {
    source.enable(trackIndex, positionUs);
    seekToInternal();
  }

  private void seekToInternal() {
    sourceIsReady = false;
    inputStreamEnded = false;
    outputStreamEnded = false;
    renderedFirstFrame = false;
  }

  @Override
  protected void onStarted() {
    droppedFrameCount = 0;
    droppedFrameAccumulationStartTimeMs = SystemClock.elapsedRealtime();
  }

  @Override
  protected void onStopped() {
    notifyAndResetDroppedFrameCount();
  }

  @Override
  protected void onReleased() {
    source.release();
  }

  @Override
  protected void onDisabled() {
    if (decoder != null) {
      decoder.release();
      decoder = null;
    }
    inputBuffer = null;
    outputBuffer = null;
    format = null;
    source.disable(trackIndex);
  }

  private void readFormat(long positionUs) throws IOException {
    int result = source.readData(trackIndex, positionUs, formatHolder, null, false);
    if (result == SampleSource.FORMAT_READ) {
      format = formatHolder.format;
    }
  }

  @Override
  public void handleMessage(int messageType, Object message) throws ExoPlaybackException {
    if (messageType == MSG_SET_SURFACE) {
      surface = (Surface) message;
      vpxVideoSurfaceView = null;
      outputRgb = true;
    } else if (messageType == MSG_SET_VPX_SURFACE_VIEW) {
      vpxVideoSurfaceView = (VpxVideoSurfaceView) message;
      surface = null;
      outputRgb = false;
    } else {
      super.handleMessage(messageType, message);
    }
  }

  private void notifyIfVideoSizeChanged(final OutputBuffer outputBuffer) {
    if (previousWidth == -1 || previousHeight == -1
        || previousWidth != outputBuffer.width || previousHeight != outputBuffer.height) {
      previousWidth = outputBuffer.width;
      previousHeight = outputBuffer.height;
      if (eventHandler != null && eventListener != null) {
        eventHandler.post(new Runnable()  {
          @Override
          public void run() {
            eventListener.onVideoSizeChanged(outputBuffer.width, outputBuffer.height);
          }
        });
      }
    }
  }

  private void notifyAndResetDroppedFrameCount() {
    if (eventHandler != null && eventListener != null && droppedFrameCount > 0) {
      long now = SystemClock.elapsedRealtime();
      final int countToNotify = droppedFrameCount;
      final long elapsedToNotify = now - droppedFrameAccumulationStartTimeMs;
      droppedFrameCount = 0;
      droppedFrameAccumulationStartTimeMs = now;
      eventHandler.post(new Runnable()  {
        @Override
        public void run() {
          eventListener.onDroppedFrames(countToNotify, elapsedToNotify);
        }
      });
    }
  }

  private void notifyDrawnToSurface(final Surface surface) {
    if (eventHandler != null && eventListener != null) {
      eventHandler.post(new Runnable()  {
        @Override
        public void run() {
          eventListener.onDrawnToSurface(surface);
        }
      });
    }
  }

  private void notifyDecoderError(final VpxDecoderException e) {
    if (eventHandler != null && eventListener != null) {
      eventHandler.post(new Runnable()  {
        @Override
        public void run() {
          eventListener.onDecoderError(e);
        }
      });
    }
  }

}
