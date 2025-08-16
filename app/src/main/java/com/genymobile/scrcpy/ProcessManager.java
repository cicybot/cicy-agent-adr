package com.genymobile.scrcpy;

import android.os.SystemClock;

import com.genymobile.scrcpy.util.Ln;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

public class ProcessManager {
    public void preRun() throws IOException {
        ProcessBuilder pb =
                new ProcessBuilder("netstat", "-tnlp");
        Process p = pb.start();
        InputStream input = p.getInputStream();
        StringBuilder res = new StringBuilder();
        int b;
        while ((b = input.read()) != -1) {
            char line = (char) b;
            res.append(line);
        }
        String[] lines = res.toString().split("\n");
        boolean portIsOnline = false;
        for (String line : lines) {
            if (line.contains("LISTEN") && line.contains("/app_process")) {
                String pid = line.split("LISTEN")[1].split("/app_process")[0].trim();
                Ln.i(line);
                Ln.i("kill pid : " + pid);
                ProcessBuilder pb1 =
                        new ProcessBuilder("kill", "-9", pid);
                pb1.start();
                portIsOnline = true;
            }
        }
        if (portIsOnline) {
            SystemClock.sleep(1000L);
        }
    }


    public void runU2() throws IOException {
        ProcessBuilder pb =
                new ProcessBuilder("app_process", "/", "com.wetest.uia2.Main");
        Map<String, String> env = pb.environment();
        env.put("CLASSPATH", "/data/local/tmp/u2.jar");
        pb.directory(new File("/data/local/tmp/"));
        pb.redirectErrorStream(true);
        pb.start();

        Ln.i("U2Process start: 9008");
    }

    public void runScrcpy(int port) throws IOException {
        ProcessBuilder pb =
                new ProcessBuilder(
                        "app_process",
                        "/",
                        "com.genymobile.scrcpy.Server",
                        BuildConfig.VERSION_NAME ,
                        "ws=true",
                        "log_level=info",
                        "port="+port);
        Map<String, String> env = pb.environment();
        env.put("CLASSPATH", "/data/local/tmp/scrcpy-server.jar");
        pb.directory(new File("/data/local/tmp/"));
        pb.redirectErrorStream(true);
        pb.start();
        Ln.i("Scrcpy start: "+port);
    }
}
