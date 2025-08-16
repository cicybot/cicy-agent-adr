package com.genymobile.scrcpy;

import android.os.Looper;

import com.genymobile.scrcpy.util.Ln;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;

public class BaseWsServer implements AsyncProcessor {
    private static final String WEBSOCKET_KEY = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private int port;
    private ServerSocket serverSocket;
    private final Set<Socket> clients = new CopyOnWriteArraySet<>();

    private volatile boolean running;

    private final AtomicBoolean stopped = new AtomicBoolean();
    private Thread thread;

    public void init(int port) {
        this.port = port;
    }
    public int getPort(){
        return this.port;
    }
    @Override
    public void start(TerminationListener listener) {
        thread = new Thread(() -> {
            Looper.prepare();
            try {
                serverSocket = new ServerSocket(port);
                running = true;
                Ln.i("Server started on port " + port);
                while (running) {
                    Socket clientSocket = serverSocket.accept();
                    new Thread(() -> handleClient(clientSocket)).start();
                }
            } catch (IOException e) {
                if (running) {
                    Ln.e("Server error: " + e.getMessage());
                }
            } finally {
                listener.onTerminated(true);
            }

        }, "recording");
        thread.start();
    }

    @Override
    public void stop() {
        if (thread != null) {
            try{
                stopServer();
            } catch (IOException e) {
                 Ln.e("stopServer error: " + e.getMessage());
             }

            stopped.set(true);
        }
    }

    @Override
    public void join() throws InterruptedException {
        if (thread != null) {
            thread.join();
        }
    }
    public String getRequestString(InputStream input) throws IOException{
        StringBuilder request = new StringBuilder();
        int b;

        while ((b = input.read()) != -1) {
            char line = (char) b;
            request.append(line);
            if(request.toString().endsWith("\r\n\r\n")){
                break;
            }
        }
        String requestStr = request.toString().trim();
        int length = 0;
        if(requestStr.contains("Content-Length: ")){
            String len = requestStr.split("Content-Length: ")[1].split("\r\n")[0];
            length = Integer.parseInt(len);
        }
        if(length > 0){
            do {
                length--;
                int b1 = input.read();
                char line = (char) b1;
                request.append(line);
            } while (length > 0);
        }
        return request.toString();
    }

    public boolean checkIsWs(String request) {
        String[] lines = request.split("\r\n");
        boolean isWs = false;
        for (String line : lines) {
            if (line.startsWith("GET /ws-api")) {
                isWs = true;
                break;
            }
        }
        return isWs;
    }
    private void handleClient(Socket clientSocket) {
        boolean isWs = false;
        try {
            InputStream input = clientSocket.getInputStream();
            OutputStream output = clientSocket.getOutputStream();
            String request = getRequestString(input);
            isWs = checkIsWs(request);
            if(isWs){
                if (performRequestHandshake(request, output)) {
                    synchronized (clients) {
                        clients.add(clientSocket);
                        Ln.i("Client connected. Total: " + clients.size());
                    }
                    while (true) {
                        try {
                            byte[] command = readFrame(input);
                            if (command == null){
                                break;
                            }
                            processCommand(command);
                        } catch (Exception e) {
                            Ln.e("RuntimeException",e);
                            break;
                        }

                    }
                }
            } else{
                handleHttp(request,input,output,clientSocket);
            }
        } catch (IOException e) {
            Ln.e("Client error: ", e);
        } finally {
            if(isWs){
                removeClient(clientSocket);
            }
        }
    }

    public boolean performRequestHandshake(String request, OutputStream output) throws IOException {
        String key = extractWebSocketKey(request);
        if (key == null) {
            return false;
        }
        String responseKey = computeResponseKey(key);

        String response = "HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: " + responseKey + "\r\n\r\n";
        output.write(response.getBytes());
        output.flush();
        return true;
    }

    private String extractWebSocketKey(String request) {
        String[] lines = request.split("\r\n");
        for (String line : lines) {
            if (line.startsWith("Sec-WebSocket-Key:")) {
                return line.substring("Sec-WebSocket-Key:".length()).trim();
            }
        }
        return null;
    }

    private String computeResponseKey(String key) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update((key + WEBSOCKET_KEY).getBytes());
            byte[] hash = sha1.digest();
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private void removeClient(Socket clientSocket) {
        synchronized (clients) {
            if (clients.remove(clientSocket)) {
                Ln.i("Client disconnected. Total: " + clients.size());
                try {
                    clientSocket.close();
                } catch (IOException e) {
                    Ln.e("Error closing client socket: " + e.getMessage());
                }
            }
        }
    }

    // 向所有客户端广播帧数据
    public void broadcastFrame(byte[] frameData) {
        Ln.d("broadcastFrame: "+clients.size());
        synchronized (clients) {
            for (Socket client : clients) {
                try {
                    OutputStream output = client.getOutputStream();
                    sendBinary(output, frameData);
                } catch (Exception e) {
                    Ln.e("Error sending frame: " + e.getMessage());
                    removeClient(client);
                }
            }
        }
    }

    public void sendBinary(OutputStream output, byte[] data) throws IOException {
        // 构建WebSocket二进制帧
        ByteBuffer buffer = ByteBuffer.allocate(10 + data.length);
        buffer.put((byte) 0x82); // FIN + Binary frame
        if (data.length <= 125) {
            buffer.put((byte) data.length);
        } else if (data.length <= 65535) {
            buffer.put((byte) 126);
            buffer.putShort((short) data.length);
        } else {
            buffer.put((byte) 127);
            buffer.putLong(data.length);
        }
        buffer.put(data);
        output.write(buffer.array(), 0, buffer.position());
        output.flush();
    }

    public void stopServer() throws IOException {
        onStopServer();
        running = false;
        if (serverSocket != null) {
            serverSocket.close();
        }

        // 关闭所有客户端连接
        synchronized (clients) {
            for (Socket client : clients) {
                try {
                    client.close();
                } catch (IOException e) {
                    Ln.e("Error closing client: " + e.getMessage());
                }
            }
            clients.clear();
        }
    }

    public byte[] readFrame(InputStream input) throws IOException {
        // 读取WebSocket帧头
        byte[] header = new byte[2];
        if (input.read(header) != 2) return null;

        int payloadLength = header[1] & 0x7F;
        if (payloadLength == 126) {
            byte[] extended = new byte[2];
            if (input.read(extended) != 2) return null;
            payloadLength = ((extended[0] & 0xFF) << 8) | (extended[1] & 0xFF);
        } else if (payloadLength == 127) {
            throw new IOException("Large payloads not supported");
        }

        // 读取掩码
        byte[] maskingKey = new byte[4];
        if ((header[1] & 0x80) != 0) {
            if (input.read(maskingKey) != 4) return null;
        }

        // 读取有效载荷
        byte[] payload = new byte[payloadLength];
        if (input.read(payload) != payloadLength) return null;

        // 应用掩码
        if ((header[1] & 0x80) != 0) {
            for (int i = 0; i < payloadLength; i++) {
                payload[i] ^= maskingKey[i % 4];
            }
        }

        return payload;
    }

    public void onStopServer(){}

    public void processCommand(byte[] command){}
    public void handleHttp(String request,InputStream input,OutputStream output,Socket clientSocket) throws IOException {}
}

