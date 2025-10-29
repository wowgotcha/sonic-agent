/*
 *   sonic-agent  Agent of Sonic Cloud Real Machine Platform.
 *   Copyright (C) 2022 SonicCloudOrg
 *
 *   This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU Affero General Public License as published
 *   by the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU Affero General Public License for more details.
 *
 *   You should have received a copy of the GNU Affero General Public License
 *   along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.cloud.sonic.agent.tests.android.scrcpy;

import com.alibaba.fastjson.JSONObject;
import com.android.ddmlib.IDevice;
import jakarta.websocket.Session;
import org.cloud.sonic.agent.bridge.android.AndroidDeviceBridgeTool;
import org.cloud.sonic.agent.common.maps.ScreenMap;
import org.cloud.sonic.agent.tests.android.AndroidTestTaskBootThread;
import org.cloud.sonic.agent.tools.BytesTool;
import org.cloud.sonic.agent.tools.PortTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.BlockingQueue;

/**
 * scrcpy socket thread
 * Forwards device video stream via port forwarding to local socket,
 * reads NALUs and puts into queue for further processing.
 *
 * Updated for scrcpy v3.1+ (e.g., codec/boundary changes, cleanup improvements).
 */
public class ScrcpyInputSocketThread extends Thread {

    private final Logger log = LoggerFactory.getLogger(ScrcpyInputSocketThread.class);

    public static final String ANDROID_INPUT_SOCKET_PRE = "android‑scrcpy‑input‑socket‑task‑%s‑%s‑%s";

    private final IDevice iDevice;
    private final BlockingQueue<byte[]> dataQueue;
    private final ScrcpyLocalThread scrcpyLocalThread;
    private final AndroidTestTaskBootThread androidTestTaskBootThread;
    private final Session session;

    private static final int BUFFER_SIZE = 10 * 1024 * 1024; // 10MB
    private static final int READ_BUFFER_SIZE = 5 * 1024;    // 5KB

    public ScrcpyInputSocketThread(IDevice iDevice,
                                   BlockingQueue<byte[]> dataQueue,
                                   ScrcpyLocalThread scrcpyLocalThread,
                                   Session session) {
        this.iDevice = iDevice;
        this.dataQueue = dataQueue;
        this.scrcpyLocalThread = scrcpyLocalThread;
        this.session = session;
        this.androidTestTaskBootThread = scrcpyLocalThread.getAndroidTestTaskBootThread();
        this.setDaemon(false);
        this.setName(androidTestTaskBootThread.formatThreadName(ANDROID_INPUT_SOCKET_PRE));
    }

    public IDevice getiDevice() {
        return iDevice;
    }

    public BlockingQueue<byte[]> getDataQueue() {
        return dataQueue;
    }

    public ScrcpyLocalThread getScrcpyLocalThread() {
        return scrcpyLocalThread;
    }

    public AndroidTestTaskBootThread getAndroidTestTaskBootThread() {
        return androidTestTaskBootThread;
    }

    public Session getSession() {
        return session;
    }

    /**
     * Clean up forwarding, session map etc.
     */
    private void cleanup(int scrcpyPort) {
        try {
            AndroidDeviceBridgeTool.removeForward(iDevice, scrcpyPort, "scrcpy");
        } catch (Exception e) {
            log.warn("Failed to remove forward for port {}: {}", scrcpyPort, e.getMessage());
        }
        if (session != null) {
            ScreenMap.getMap().remove(session);
        }
    }

    @Override
    public void run() {
        int scrcpyPort = PortTool.getPort();
        log.info("Forwarding device {} scrcpy port {}", iDevice, scrcpyPort);
        AndroidDeviceBridgeTool.forward(iDevice, scrcpyPort, "scrcpy");

        try (Socket videoSocket = new Socket()) {
            videoSocket.connect(new InetSocketAddress("localhost", scrcpyPort));
            log.info("Connected to scrcpy socket on port {}", scrcpyPort);

            try (InputStream inputStream = videoSocket.getInputStream()) {
                // On connect, send screen size to client if needed
                if (videoSocket.isConnected()) {
                    String sizeTotal = AndroidDeviceBridgeTool.getScreenSize(iDevice);
                    if (sizeTotal != null && sizeTotal.contains("x")) {
                        String[] parts = sizeTotal.split("x");
                        JSONObject size = new JSONObject();
                        size.put("msg", "size");
                        size.put("width", parts[0]);
                        size.put("height", parts[1]);
                        BytesTool.sendText(session, size.toJSONString());
                    } else {
                        log.warn("Unexpected screen size string: {}", sizeTotal);
                    }
                }

                byte[] buffer = new byte[BUFFER_SIZE];
                int bufferLength = 0;

                while (scrcpyLocalThread.isAlive() && !Thread.currentThread().isInterrupted()) {
                    int readLength = inputStream.read(buffer, bufferLength, READ_BUFFER_SIZE);
                    if (readLength < 0) {
                        // End of stream
                        log.info("End of stream from scrcpy socket");
                        break;
                    }
                    if (readLength == 0) {
                        // No data, loop again (or wait)
                        continue;
                    }

                    bufferLength += readLength;

                    // Look for NALU boundary 0x00 0x00 0x00 0x01
                    // Note: scrcpy v3.1+ may use alternate encodings (AV1 etc),
                    // so you may need to adjust this logic if you receive different markers.
                    for (int i = 5; i < bufferLength - 4; i++) {
                        if (buffer[i] == 0x00 &&
                            buffer[i+1] == 0x00 &&
                            buffer[i+2] == 0x00 &&
                            buffer[i+3] == 0x01) {
                            int naluIndex = i;
                            byte[] naluBuffer = new byte[naluIndex];
                            System.arraycopy(buffer, 0, naluBuffer, 0, naluIndex);
                            dataQueue.add(naluBuffer);
                            // shift remaining bytes to front
                            bufferLength -= naluIndex;
                            System.arraycopy(buffer, naluIndex, buffer, 0, bufferLength);
                            i = 5; // reset scan index
                        }
                    }

                    // Optional: if buffer gets too large (i.e., no boundary found),
                    // you may want to flush entire buffer or handle differently to avoid overflow.
                    if (bufferLength >= BUFFER_SIZE - READ_BUFFER_SIZE) {
                        log.warn("Buffer nearing capacity, flushing {} bytes", bufferLength);
                        byte[] leftover = new byte[bufferLength];
                        System.arraycopy(buffer, 0, leftover, 0, bufferLength);
                        dataQueue.add(leftover);
                        bufferLength = 0;
                    }
                }
            }
        } catch (IOException e) {
            log.error("IOException in scrcpy input socket thread", e);
        } finally {
            if (scrcpyLocalThread.isAlive()) {
                scrcpyLocalThread.interrupt();
                log.info("scrcpyLocalThread interrupted due to input socket termination");
            }
            cleanup(scrcpyPort);
            log.info("Cleaned up scrcpy port forwarding and session map");
        }
    }
}
