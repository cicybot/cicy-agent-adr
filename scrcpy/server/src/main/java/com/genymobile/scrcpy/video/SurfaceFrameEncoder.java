package com.genymobile.scrcpy.video;

import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;

import com.genymobile.scrcpy.AsyncProcessor;
import com.genymobile.scrcpy.RecordingServer;
import com.genymobile.scrcpy.device.ConfigurationException;
import com.genymobile.scrcpy.device.Size;
import com.genymobile.scrcpy.util.IO;
import com.genymobile.scrcpy.util.Ln;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

public class SurfaceFrameEncoder implements AsyncProcessor {
    private static final int MAX_QUALITY = 99;
    private static final int DEFAULT_MAX_IMAGES = 2;

    private final SurfaceCapture capture;
    private final CaptureReset reset = new CaptureReset();

    private Thread thread;
    private int maxImages = DEFAULT_MAX_IMAGES;
    private RecordingServer recordingServer;

    private ImageReader imageReader;
    private Surface surface;

    public SurfaceFrameEncoder(SurfaceCapture capture) {
        this.capture = capture;
    }

    public void setMaxImages(int maxImages) {
        this.maxImages = Math.max(1, maxImages); // Ensure at least 1 image
    }

    public void setRecordingServer(RecordingServer recordingServer) {
        this.recordingServer = recordingServer;
    }

    public synchronized void recreateImageReader() {
        Ln.i("Recreating ImageReader");
        cleanupResources();

        try {
            capture.init(reset);
            capture.prepare();
            createImageReaderSurface();
        } catch (IOException | ConfigurationException e) {
            Ln.e("Failed to recreate ImageReader", e);
        }
    }
    public Size getSize(){
        return capture.getSize();
    }
    private synchronized void createImageReaderSurface() throws IOException {

        Size size = capture.getSize();
        Ln.i("size width: "+size.getWidth());
        Ln.i("size height: "+size.getHeight());
        imageReader = ImageReader.newInstance(
                size.getWidth(),
                size.getHeight(),
                PixelFormat.RGBA_8888,
                maxImages);

        imageReader.setOnImageAvailableListener(reader -> {
            try (Image image = reader.acquireLatestImage()) {
                if (image != null && recordingServer != null) {
                    recordingServer.writeScreenImage(image);
                }
            } catch (IllegalStateException e) {
                Ln.e("Image processing error: " + e.getMessage());
                recreateImageReader();
            } catch (Exception e) {
                Ln.e("Unexpected error processing image", e);
            }
        }, new Handler(Looper.getMainLooper()));

        surface = imageReader.getSurface();
        capture.start(surface);
    }

    private void streamCapture() throws IOException, ConfigurationException {
        try {
            capture.init(reset);
            capture.prepare();
            createImageReaderSurface();
            Looper.loop();
        } finally {
            cleanupResources();
        }
    }

    private void cleanupResources() {
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }

        if (surface != null) {
            surface.release();
            surface = null;
        }

        capture.release();
        capture.stop();
    }

    @Override
    public void start(TerminationListener listener) {
        thread = new Thread(() -> {
            Looper.prepare();
            try {
                streamCapture();
            } catch (ConfigurationException e) {
                // User-friendly error already logged
            } catch (IOException e) {
                if (!IO.isBrokenPipe(e)) {
                    Ln.e("Video encoding error", e);
                }
            } finally {
                Ln.d("Screen streaming stopped");
                listener.onTerminated(true);
            }
        }, "video");
        thread.start();
    }

    @Override
    public synchronized void stop() {
        Ln.i("Stopping SurfaceFrameEncoder");
        reset.reset();

        if (thread != null) {
            cleanupResources();
            thread.interrupt();
        }
    }

    @Override
    public void join() throws InterruptedException {
        if (thread != null) {
            thread.join();
        }
    }
}