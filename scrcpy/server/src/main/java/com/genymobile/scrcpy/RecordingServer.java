package com.genymobile.scrcpy;

import static com.genymobile.scrcpy.util.LogUtils.buildAppListMessage;

import android.graphics.Bitmap;
import android.media.Image;
import android.net.Uri;
import android.os.SystemClock;

import com.genymobile.scrcpy.control.ControllerFrame;
import com.genymobile.scrcpy.device.Device;
import com.genymobile.scrcpy.device.DeviceApp;
import com.genymobile.scrcpy.device.Size;
import com.genymobile.scrcpy.device.StreamerVideo;
import com.genymobile.scrcpy.util.Ln;
import com.genymobile.scrcpy.video.SurfaceFrameEncoder;
import com.genymobile.scrcpy.video.SurfaceVideoEncoder;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

public class RecordingServer extends BaseWsServer {

    class ImageBuffer {
        public final ByteBuffer buffer;
        public final int width;
        public final int height;

        public ImageBuffer(ByteBuffer buffer, int width, int height) {
            this.buffer = buffer;
            this.width = width;
            this.height = height;
        }
    }
    class ScreenClient {
        public final Socket socket;
        public final int quality;
        public final float scale;

        public ScreenClient(Socket socket, int quality, float scale) {
            this.socket = socket;
            this.quality = quality;
            this.scale = scale;
        }
    }
    private long lastTimestamp = 0;

    private ImageBuffer currentImage;
    private final Object imageLock = new Object(); // For thread safety

    private final Set<ScreenClient> screenSubClients = new CopyOnWriteArraySet<>();
    private final Set<Socket> screenStreamClients = new CopyOnWriteArraySet<>();

    private ControllerFrame controller = null;
    private SurfaceFrameEncoder surfaceEncoder;
    private SurfaceVideoEncoder surfaceVideoEncoder;
    private StreamerVideo streamerVideo;

    public RecordingServer(int port) {
        init(port);
    }

    public int getScreenSubClientsSize() {
        return screenSubClients.size();
    }

    public void httpClient(String method, String urlString, String body, OutputStream output) {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(urlString).openConnection();
            Ln.i(method + " : " + urlString);
            Ln.d("body: " + body);
            try {
                connection.setRequestMethod(method.toUpperCase());
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Length", String.valueOf(body.length()));

                if (!body.isEmpty()) {
                    connection.setRequestProperty("Accept", "application/json");
                    connection.setRequestProperty("Content-Type", "application/json");
                    byte[] input = body.getBytes(StandardCharsets.UTF_8);
                    connection.getOutputStream().write(input, 0, input.length);
                }
                int statusCode = connection.getResponseCode();

                InputStream in = (statusCode > 299) ? connection.getErrorStream() : connection.getInputStream();

                // Combine metadata and image data
                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }

                String contentType = connection.getContentType();
                if (contentType == null || contentType.isEmpty()) {
                    contentType = "text/plain";
                }
                Ln.d("res : " + urlString +" "  + statusCode + " " + connection.getResponseMessage());
                Ln.d("ContentLength : " + outputStream.size());
                Ln.d("contentType : " + contentType);

                output.write(buildResponseHeader(
                        connection.getResponseCode(),
                        connection.getResponseMessage(),
                        outputStream.size(),
                        contentType,
                        "close"
                ));

                output.write(outputStream.toByteArray());
                output.flush();
            } catch (Exception e) {
                Ln.e("httpClient error: ", e);

                output.write(buildResponseHeader(
                        500,
                        "Internal Server Error",
                        0,
                        "text/plain",
                        "close"
                ));
                output.flush();
            } finally {
                connection.disconnect();
            }
        } catch (Exception e) {
            Ln.e("httpClient error: ", e);
        }
    }

    @Override
    public void handleHttp(String request, InputStream input, OutputStream output, Socket clientSocket) throws IOException {
        String[] lines = request.split("\r\n");
        String url = "";
        String method = "";
        String host = "";
        int i = 0;
        for (String line : lines) {
            i++;
            if (i == 1) {
                method = line.split(" ")[0];
                url = line.replace(method + " ", "").split("HTTP")[0];
            } else if (line.startsWith("Host:")) {
                host = line.replace("Host: ", "");
            }
        }
        String body;
        if (request.contains("\r\n\r\n")) {
            String[] lines1 = request.split("\r\n\r\n");
            if (lines1.length == 2) {
                body = lines1[1].trim();
            } else {
                body = "";
            }
        } else {
            body = "";
        }

        Ln.d("[+] " + method + " " + host + " " + url);
        if (method.equals("HEAD") || method.equals("OPTIONS")) {
            output.write(buildResponseOk(0,"text/plain"));
            output.flush();
            return;
        }
        if (url.equals("/")) {
            output.write(buildResponseOk(5,"text/plain"));
            output.flush();
        } else if (url.startsWith("/jsonrpc")) {
            Thread thread = new Thread(() -> {
                httpClient("POST", "http://127.0.0.1:9008/jsonrpc/0", body, output);
            }, "jsonrpc");
            thread.start();
        } else if (url.startsWith("/agent")) {
            String finalMethod = method;
            String finalUrl = url;
            Thread thread = new Thread(() -> {
                httpClient(finalMethod, "http://127.0.0.1:9012"+ finalUrl, body, output);
            }, "agent");
            thread.start();
        } else if (url.startsWith("/clash")) {
            String finalMethod = method;
            String finalUrl = url;
            Thread thread = new Thread(() -> {
                httpClient(finalMethod, "http://127.0.0.1:9011"+ finalUrl, body, output);
            }, "agent");
            thread.start();
        }else if (url.startsWith("/apps/list")) {
            List<DeviceApp> apps = Device.listApps();
            String appsRes = buildAppListMessage("List of apps:", apps);
            output.write(buildResponseOk(appsRes.getBytes().length,"text/plain"));
            output.write(appsRes.getBytes());
            output.flush();
        } else if (url.startsWith("/screen/reset")) {
            Uri uri = Uri.parse(url);
            String maxImages = uri.getQueryParameter("m");
            if (maxImages == null) {
                maxImages = "2";
            }
            this.surfaceEncoder.setMaxImages(Integer.parseInt(maxImages.trim()));
            this.surfaceEncoder.recreateImageReader();
            output.write(buildResponseOk(0,"text/plain"));
            output.flush();
        } else if (url.startsWith("/screen/size")) {
            Size size = this.surfaceEncoder.getSize();
            String bodyRes = "{\"size\":\""+size.getWidth()+"/"+size.getHeight()+"\"}";
            output.write(buildResponseOk(bodyRes.length(),"application/json"));
            output.write(bodyRes.getBytes());
            output.flush();
        } else if (url.startsWith("/deviceInfo")) {
            String device = getDeviceInfo();
            output.write(buildResponseOk(device.getBytes().length,"text/plain"));
            output.write(device.getBytes());
            output.flush();
        } else if (url.startsWith("/shell/exec")) {
            String res = shellCmd(body);
            output.write(buildResponseOk(res.getBytes().length,"text/plain"));
            output.write(res.getBytes());
            output.flush();
        } else if (url.startsWith("/shell/cmd")) {
            String res = shellCmd(body);
            output.write(buildResponseOk(res.getBytes().length,"text/plain"));
            output.write(res.getBytes());
            output.flush();
        } else if (url.startsWith("/shell/backend")) {
            output.write(buildResponseOk(2, "text/plain")); // "OK" is 2 bytes
            output.write("OK".getBytes());
            output.flush();

            new Thread(() -> {
                shellCmd(body); // Execute in background
            }).start();

        } else if (url.startsWith("/screen.jpeg")) {
            Uri uri = Uri.parse(url);
            String qualityParam = uri.getQueryParameter("q");
            String scaleParam = uri.getQueryParameter("s");
            int quality = qualityParam != null ? Integer.parseInt(qualityParam) : 80;
            float scale = scaleParam != null ? Float.parseFloat(scaleParam) : 0.5f;
            output.write(buildResponseKeepLive("image/jpeg"));
            output.flush();
            synchronized (screenSubClients) {
                screenSubClients.add(new ScreenClient(clientSocket,quality,scale));
                Ln.i("screenSubClients connected. Total: " + screenSubClients.size());
            }
            while (this.screenSubClients.contains(clientSocket)) {
                SystemClock.sleep(500);
            }
        }else if (url.startsWith("/screenImg.jpeg")) {
            Uri uri = Uri.parse(url);
            String qualityParam = uri.getQueryParameter("q");
            String scaleParam = uri.getQueryParameter("s");
            int quality = qualityParam != null ? Integer.parseInt(qualityParam) : 80;
            float scale = scaleParam != null ? Float.parseFloat(scaleParam) : 0.5f;
            synchronized (imageLock) {
                ImageBuffer img =  this.currentImage;
                try {
                    int bufferSizeCapacity = img.buffer.capacity();
                    //Ln.i("screenImg size: " + bufferSizeCapacity);
                    if(bufferSizeCapacity > 0){
                        byte[] b = processImage(img,quality,scale);
                        output.write(buildResponseOk(b.length,"image/jpeg"));
                        output.write(b);
                    }else{
                        output.write(buildResponseOk(0,"image/jpeg"));
                    }
                } catch (Exception e) {
                    //Ln.e("get screenImg.jpg error: ",e);
                    output.write(buildResponseOk(0,"image/jpeg"));
                }
                output.flush();
            }

        }
        else if (url.startsWith("/stream.avc")) {
            String response = "HTTP/1.1 200 OK\r\n" +
                    "Connection: keep-alive\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                    "Access-Control-Allow-Headers: Content-Type\n" +
                    "Transfer-Encoding: chunked\r\n" +
                    "Content-Type: video/avc\r\n\r\n";

            output.write(response.getBytes());
            output.write(streamerVideo.getVideoHeader(surfaceEncoder.getSize()).array());
            output.flush();

            synchronized (screenStreamClients) {
                screenStreamClients.add(clientSocket);
                if (screenStreamClients.size() == 1) {
                    // First client - start encoding
                    surfaceVideoEncoder.setHasClients(true);
                }
            }
            while (screenStreamClients.contains(clientSocket) && !clientSocket.isClosed()) {
                SystemClock.sleep(100);
            }

            synchronized (screenStreamClients) {
                screenStreamClients.remove(clientSocket);
                if (screenStreamClients.isEmpty()) {
                    // Last client disconnected
                    surfaceVideoEncoder.setHasClients(false);
                }
            }
        }
        else if (url.startsWith("/controller") && this.controller != null) {
            this.controller.processHttpCmd(body, output);
        } else {
            output.write(buildResponseOk(5,"text/plain"));
            output.write(BuildConfig.VERSION_NAME.getBytes());
            output.flush();
        }
    }

    public byte[] buildResponseKeepLive(String contentType){
        String res =  "HTTP/1.1 200 OK\r\n" +
                "Connection: keep-alive\r\n" +
                "Content-Type: "+contentType+"\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                "Access-Control-Allow-Headers: Content-Type\r\n\r\n";
        return res.getBytes();
    }
    public byte[] buildResponseOk(int contentLen, String contentType){
        return buildResponseHeader(200,"OK",contentLen,contentType,"close");
    }

    public byte[] buildResponseHeader(int statusCode, String responseMessage, int contentLen, String contentType, String connection){
        String res =  "HTTP/1.1 "+statusCode+" "+responseMessage+"\r\n" +
                "Content-Length: "+contentLen+"\r\n" +
                "Connection: "+connection+"\r\n" +
                "Content-Type: "+contentType+"\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                "Access-Control-Allow-Headers: Content-Type\r\n\r\n";
        return res.getBytes();
    }

    public void setController(ControllerFrame controller) {
        this.controller = controller;
    }

    public void setSurfaceEncoder(SurfaceFrameEncoder surfaceEncoder) {
        this.surfaceEncoder = surfaceEncoder;
    }

    public void setStreamerVideo(StreamerVideo streamerVideo) {
        this.streamerVideo = streamerVideo;
    }

    public void setSurfaceVideoEncoder(SurfaceVideoEncoder surfaceVideoEncoder) {
        this.surfaceVideoEncoder = surfaceVideoEncoder;
    }

    public void writeVideoScreen(byte[] imageBytes) {
        Ln.d("writeVideoScreen: " + screenSubClients.size());
        synchronized (screenStreamClients) {
            for (Socket client : screenStreamClients) {
                try {
                    OutputStream output = client.getOutputStream();
                    output.write(imageBytes);
                    output.flush();
                } catch (Exception e) {
                    Ln.e("Error writeVideoScreen: " + e.getMessage());
                    screenStreamClients.remove(client);
                }
            }
        }
    }

    public void writeScreenImage(Image image) {
        synchronized (imageLock) {
            try {
                Image.Plane plane = image.getPlanes()[0];
                ByteBuffer buffer = plane.getBuffer();
                int width = image.getWidth();
                int height = image.getHeight();
                int bufferSizeCapacity = buffer.capacity();
                if(bufferSizeCapacity > 0){
                    this.currentImage = new ImageBuffer(buffer,width,height);
                    lastTimestamp = System.currentTimeMillis();
                    synchronized (screenSubClients) {
                        for (ScreenClient client : screenSubClients) {
                            try {
                                OutputStream output = client.socket.getOutputStream();
                                byte[] imageBytes = processImage(this.currentImage,client.quality,client.scale);
                                output.write(imageBytes);
                                output.flush();
                            } catch (Exception e) {
                                Ln.e("Error writeScreen: " + e.getMessage());
                                screenSubClients.remove(client);
                            }
                        }
                    }
                }


            }finally {
                image.close();
            }
        }
        //Ln.d("writeScreen: " + screenSubClients.size());

    }

    public void clearCurrentImage() {
        synchronized (imageLock) {
            this.currentImage = null;
        }
    }
    @Override
    public void onStopServer(){
        clearCurrentImage();
    }

    private byte[] processImage(ImageBuffer image,int quality,float scale) throws IOException {
        ByteBuffer buffer = image.buffer;
        buffer.position(0);
        int width = image.width;
        int height = image.height;
        Bitmap finalBitmap = Bitmap.createBitmap(
                width, height, Bitmap.Config.ARGB_8888);

        finalBitmap.copyPixelsFromBuffer(buffer);

        if (scale != 1.0) {
            Bitmap scaled = Bitmap.createScaledBitmap(
                    finalBitmap,
                    (int)(width * scale),
                    (int)(height * scale),
                    true);
            finalBitmap.recycle();
            finalBitmap = scaled;
        }

        ByteArrayOutputStream jpegStream = new ByteArrayOutputStream();
        finalBitmap.compress(Bitmap.CompressFormat.JPEG, quality, jpegStream);
        byte[] jpegData = jpegStream.toByteArray();
        long currentTs = System.currentTimeMillis();

        int seconds = (int) (currentTs / 1000);
        int millis = (int) (currentTs % 1000);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        try (DataOutputStream dos = new DataOutputStream(outputStream)) {
            dos.writeInt(jpegData.length);
            dos.write(jpegData);
            dos.writeInt(width);
            dos.writeInt(height);
            dos.writeInt(quality);
            dos.writeInt((int)(scale * 100));
            dos.writeInt(seconds);
            dos.writeInt(millis);
        }
        return outputStream.toByteArray();
    }

    public String getDeviceInfo() throws IOException {
        ProcessBuilder pb =
                new ProcessBuilder("sh","/data/local/tmp/device.sh", "deviceInfo");
        Process p = pb.start();
        InputStream input = p.getInputStream();
        StringBuilder res = new StringBuilder();
        int b;
        while ((b = input.read()) != -1) {
            char line = (char) b;
            res.append(line);
        }
        return res.toString().trim();
    }
    public String shellCmd(String cmd) {
        ProcessBuilder pb = new ProcessBuilder("sh", "-c", cmd);
        pb.redirectErrorStream(true);
        Process p = null;
        Ln.i("1");
        try {
            p = pb.start();
            try (InputStream input = p.getInputStream();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(input))) {
                Ln.i("2");
                StringBuilder res = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    res.append(line).append("\n");
                }
                Ln.i(res.toString());
                // Wait for the process to complete
                int exitCode = p.waitFor();
                Ln.i(String.valueOf(exitCode));
                if (exitCode != 0) {
                    return "Command failed with exit code: " + exitCode;
                }
                return res.toString().trim();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // Clean up the process if it was interrupted
            p.destroy();
            return "Command execution was interrupted";
        } catch (IOException e) {
            return "IO error executing command: " + e.getMessage();
        } finally {
            if (p != null) {
                p.destroy(); // Ensure process is cleaned up
            }
        }
    }
    public String shellExec(String cmd) throws IOException {
        String[] cmdParts = cmd.split("\\s+");
        ProcessBuilder pb =
                new ProcessBuilder(cmdParts);
        Process p = pb.start();
        InputStream input = p.getInputStream();
        StringBuilder res = new StringBuilder();
        int b;
        while ((b = input.read()) != -1) {
            char line = (char) b;
            res.append(line);
        }
        return res.toString().trim();
    }
}

