package com.example.service

import android.util.Log
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList

/**
 * High-performance, zero-dependency embedded HTTP server for USB screen sharing.
 *
 * Serves:
 * - GET /              -> Ultra-responsive Web Viewer UI (optimized even for year 2000 laptops)
 * - GET /stream        -> Real-time zero-lag MJPEG stream (multipart/x-mixed-replace)
 * - GET /snapshot      -> Current frame JPEG
 * - GET /status        -> Real-time JSON telemetry (FPS, resolution, clients)
 * - GET /screencraft-usb.bat -> 1-click Windows connector script
 * - GET /screencraft-usb.sh  -> 1-click Linux/Mac connector script
 */
class UsbStreamServer(
    val port: Int = 8080,
    private val onClientCountChanged: (Int) -> Unit = {}
) {
    private var serverSocket: ServerSocket? = null
    @Volatile private var isRunning = false
    private var acceptThread: Thread? = null

    // Dedicated client handler class that streams independently without blocking capture thread
    private class ClientHandler(
        val socket: Socket,
        val output: OutputStream
    ) {
        @Volatile var isAlive = true
        private val lock = Object()
        @Volatile private var pendingFrame: ByteArray? = null

        fun pushFrame(frame: ByteArray) {
            // Replace pending frame: always drop stale frames if client is slow!
            pendingFrame = frame
            synchronized(lock) {
                lock.notify()
            }
        }

        fun startStreamLoop(onClose: (ClientHandler) -> Unit) {
            Thread({
                try {
                    while (isAlive && !socket.isClosed) {
                        var frameToSend: ByteArray? = null
                        synchronized(lock) {
                            while (isAlive && pendingFrame == null) {
                                lock.wait(50)
                            }
                            frameToSend = pendingFrame
                            pendingFrame = null
                        }

                        if (frameToSend != null) {
                            val header = "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${frameToSend!!.size}\r\n\r\n".toByteArray()
                            output.write(header)
                            output.write(frameToSend)
                            output.write("\r\n".toByteArray())
                            output.flush()
                        }
                    }
                } catch (e: Exception) {
                    // Socket closed or broken pipe
                } finally {
                    isAlive = false
                    try { socket.close() } catch (_: Exception) {}
                    onClose(this)
                }
            }, "MjpegClient-${socket.port}").apply {
                priority = Thread.NORM_PRIORITY + 1
                isDaemon = true
                start()
            }
        }
    }

    private val activeClients = CopyOnWriteArrayList<ClientHandler>()

    @Volatile var latestFrame: ByteArray? = null
    @Volatile var currentFps: Int = 0
    @Volatile var streamWidth: Int = 360
    @Volatile var streamHeight: Int = 760

    companion object {
        private const val TAG = "UsbStreamServer"
    }

    fun start(): Boolean {
        return try {
            val ss = ServerSocket(port)
            serverSocket = ss
            isRunning = true

            acceptThread = Thread({
                Log.d(TAG, "USB Stream Server listening on port $port")
                while (isRunning && !ss.isClosed) {
                    try {
                        val client = ss.accept()
                        Thread({ handleClient(client) }, "UsbClient-${client.inetAddress.hostAddress}").start()
                    } catch (e: SocketException) {
                        break
                    } catch (e: Exception) {
                        Log.w(TAG, "Error accepting client: ${e.message}")
                    }
                }
            }, "UsbStreamServerAccept").apply {
                isDaemon = true
                start()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind USB Stream Server on port $port: ${e.message}", e)
            false
        }
    }

    fun broadcastFrame(jpegBytes: ByteArray) {
        latestFrame = jpegBytes
        if (activeClients.isEmpty()) return

        for (client in activeClients) {
            if (client.isAlive) {
                client.pushFrame(jpegBytes)
            }
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.tcpNoDelay = true // Zero TCP buffering delay over USB / Ethernet
            socket.sendBufferSize = 64 * 1024
            socket.soTimeout = 10000

            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val rawOutput = socket.getOutputStream()
            val out = BufferedOutputStream(rawOutput, 32 * 1024)

            val requestLine = reader.readLine() ?: return socket.close()
            val tokens = requestLine.split(" ")
            if (tokens.size < 2) return socket.close()

            val method = tokens[0]
            val path = tokens[1].split("?")[0]

            // Read remaining HTTP request headers
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line!!.isEmpty()) break
            }

            when {
                path == "/" || path == "/index.html" -> serveHtml(out)
                path == "/stream" -> serveMjpegStream(socket, rawOutput)
                path == "/snapshot" -> serveSnapshot(out)
                path == "/status" -> serveStatus(out)
                path == "/screencraft-usb.bat" -> serveWindowsScript(out)
                path == "/screencraft-usb.sh" -> serveUnixScript(out)
                else -> {
                    val notFound = "HTTP/1.1 404 Not Found\r\nContent-Length: 9\r\n\r\nNot Found"
                    out.write(notFound.toByteArray())
                    out.flush()
                    socket.close()
                }
            }
        } catch (e: Exception) {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun serveHtml(out: OutputStream) {
        val htmlBytes = HTML_VIEWER_PAGE.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/html; charset=UTF-8\r\n" +
                "Content-Length: ${htmlBytes.size}\r\n" +
                "Connection: close\r\n" +
                "Cache-Control: no-cache, no-store, must-revalidate\r\n\r\n"
        out.write(header.toByteArray())
        out.write(htmlBytes)
        out.flush()
    }

    private fun serveMjpegStream(socket: Socket, rawOutput: OutputStream) {
        val header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: multipart/x-mixed-replace; boundary=--frame\r\n" +
                "Cache-Control: no-cache, no-store, must-revalidate, pre-check=0, post-check=0, max-age=0\r\n" +
                "Pragma: no-cache\r\n" +
                "Expires: 0\r\n" +
                "Connection: close\r\n\r\n"
        rawOutput.write(header.toByteArray())
        rawOutput.flush()

        val client = ClientHandler(socket, rawOutput)
        activeClients.add(client)
        onClientCountChanged(activeClients.size)

        // Send latest frame immediately so client has initial image
        latestFrame?.let { client.pushFrame(it) }

        // Start dedicated streaming loop on background thread
        client.startStreamLoop { deadClient ->
            activeClients.remove(deadClient)
            onClientCountChanged(activeClients.size)
        }
    }

    private fun serveSnapshot(out: OutputStream) {
        val frame = latestFrame
        if (frame != null) {
            val header = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: image/jpeg\r\n" +
                    "Content-Length: ${frame.size}\r\n" +
                    "Cache-Control: no-cache\r\n" +
                    "Connection: close\r\n\r\n"
            out.write(header.toByteArray())
            out.write(frame)
            out.flush()
        } else {
            val empty = "HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\n\r\n"
            out.write(empty.toByteArray())
            out.flush()
        }
    }

    private fun serveStatus(out: OutputStream) {
        val json = """{"status":"streaming","fps":$currentFps,"width":$streamWidth,"height":$streamHeight,"clients":${activeClients.size}}"""
        val bytes = json.toByteArray()
        val header = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n"
        out.write(header.toByteArray())
        out.write(bytes)
        out.flush()
    }

    private fun serveWindowsScript(out: OutputStream) {
        val script = """@echo off
title ScreenCraft USB Screen Mirror
echo ==========================================================
echo  ScreenCraft USB Real-Time Mirror - 1-Click Connect
echo ==========================================================
echo.
echo [1/2] Forwarding port 8080 via ADB...
adb forward tcp:8080 tcp:8080
if %errorlevel% neq 0 (
    echo.
    echo [ERROR] ADB forward failed!
    echo Ensure phone is connected with USB Debugging enabled.
    pause
    exit /b
)
echo [2/2] USB tunnel established successfully!
echo Opening browser: http://localhost:8080
start http://localhost:8080
echo.
echo ScreenCraft is now mirroring directly over USB!
pause
"""
        val bytes = script.toByteArray()
        val header = "HTTP/1.1 200 OK\r\nContent-Type: application/x-bat\r\nContent-Disposition: attachment; filename=\"screencraft-usb.bat\"\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        out.write(header.toByteArray())
        out.write(bytes)
        out.flush()
    }

    private fun serveUnixScript(out: OutputStream) {
        val script = """#!/bin/bash
echo "=========================================================="
echo " ScreenCraft USB Real-Time Mirror - 1-Click Connect"
echo "=========================================================="
echo ""
echo "[1/2] Forwarding port 8080 via ADB..."
adb forward tcp:8080 tcp:8080
if [ $? -ne 0 ]; then
    echo "[ERROR] ADB forward failed. Ensure phone has USB Debugging enabled."
    exit 1
fi
echo "[2/2] USB tunnel established: http://localhost:8080"
if command -v xdg-open > /dev/null; then
    xdg-open http://localhost:8080
elif command -v open > /dev/null; then
    open http://localhost:8080
fi
"""
        val bytes = script.toByteArray()
        val header = "HTTP/1.1 200 OK\r\nContent-Type: text/x-sh\r\nContent-Disposition: attachment; filename=\"screencraft-usb.sh\"\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        out.write(header.toByteArray())
        out.write(bytes)
        out.flush()
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null

        for (client in activeClients) {
            client.isAlive = false
            try { client.socket.close() } catch (_: Exception) {}
        }
        activeClients.clear()
        onClientCountChanged(0)

        try {
            acceptThread?.join(500)
        } catch (_: Exception) {}
        acceptThread = null
    }

    private val HTML_VIEWER_PAGE = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>ScreenCraft USB Live Mirror</title>
<style>
  :root {
    --bg-color: #0b0f19;
    --card-bg: #111827;
    --accent-red: #ef4444;
    --accent-blue: #38bdf8;
    --accent-green: #10b981;
    --text-primary: #f9fafb;
    --text-secondary: #9ca3af;
  }
  * { box-sizing: border-box; margin: 0; padding: 0; }
  body {
    background-color: var(--bg-color);
    color: var(--text-primary);
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
    display: flex;
    flex-direction: column;
    height: 100vh;
    overflow: hidden;
  }
  /* Top App Bar */
  header {
    background-color: var(--card-bg);
    border-bottom: 1px solid rgba(255,255,255,0.08);
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 8px 16px;
    height: 52px;
    flex-shrink: 0;
  }
  .brand {
    display: flex;
    align-items: center;
    gap: 8px;
    font-weight: 800;
    font-size: 1.1rem;
    letter-spacing: -0.5px;
  }
  .brand-dot {
    width: 12px;
    height: 12px;
    border-radius: 50%;
    background-color: var(--accent-red);
    box-shadow: 0 0 10px var(--accent-red);
    animation: pulse 2s infinite;
  }
  @keyframes pulse {
    0% { transform: scale(0.95); opacity: 0.8; }
    50% { transform: scale(1.15); opacity: 1; }
    100% { transform: scale(0.95); opacity: 0.8; }
  }
  .badges {
    display: flex;
    align-items: center;
    gap: 8px;
  }
  .badge {
    background: rgba(56, 189, 248, 0.12);
    color: var(--accent-blue);
    padding: 4px 10px;
    border-radius: 999px;
    font-size: 0.75rem;
    font-weight: 600;
    display: inline-flex;
    align-items: center;
    gap: 6px;
  }
  .badge.live {
    background: rgba(16, 185, 129, 0.15);
    color: var(--accent-green);
  }
  .badge.potato {
    background: rgba(245, 158, 11, 0.15);
    color: #fbbf24;
  }
  .controls {
    display: flex;
    align-items: center;
    gap: 6px;
  }
  button {
    background-color: rgba(255,255,255,0.06);
    border: 1px solid rgba(255,255,255,0.1);
    color: var(--text-primary);
    padding: 6px 12px;
    border-radius: 8px;
    font-size: 0.8rem;
    font-weight: 600;
    cursor: pointer;
    transition: all 0.15s ease;
    display: inline-flex;
    align-items: center;
    gap: 4px;
  }
  button:hover {
    background-color: rgba(255,255,255,0.12);
    border-color: rgba(255,255,255,0.25);
  }
  button.active {
    background-color: var(--accent-blue);
    color: #000;
    border-color: var(--accent-blue);
  }
  /* Main Viewer Area */
  main {
    flex: 1;
    display: flex;
    align-items: center;
    justify-content: center;
    background-color: #000;
    overflow: hidden;
    position: relative;
    user-select: none;
  }
  .screen-wrapper {
    position: relative;
    display: flex;
    align-items: center;
    justify-content: center;
    max-width: 100%;
    max-height: 100%;
    transition: transform 0.2s cubic-bezier(0.16, 1, 0.3, 1);
  }
  #stream-img {
    max-width: 100vw;
    max-height: calc(100vh - 52px - 28px);
    object-fit: contain;
    border-radius: 4px;
    box-shadow: 0 10px 40px rgba(0,0,0,0.8);
    image-rendering: -webkit-optimize-contrast;
  }
  /* Potato Mode (Vintage 2000s PC optimization) */
  body.potato-mode #stream-img {
    box-shadow: none !important;
    border-radius: 0 !important;
    image-rendering: pixelated;
  }
  body.potato-mode header {
    box-shadow: none !important;
  }
  /* Bottom Info Bar */
  footer {
    height: 28px;
    background-color: var(--card-bg);
    border-top: 1px solid rgba(255,255,255,0.06);
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 0 16px;
    font-size: 0.72rem;
    color: var(--text-secondary);
    flex-shrink: 0;
  }
  .shortcuts span {
    background: rgba(255,255,255,0.08);
    padding: 2px 6px;
    border-radius: 4px;
    margin: 0 2px;
    color: var(--text-primary);
  }
</style>
</head>
<body>
  <header>
    <div class="brand">
      <div class="brand-dot"></div>
      <span>ScreenCraft</span>
      <span style="font-size: 0.75rem; color: var(--accent-blue); font-weight: 500;">USB Mirror</span>
    </div>

    <div class="badges">
      <div class="badge live">● USB LIVE</div>
      <div class="badge" id="fps-badge">FPS: --</div>
      <div class="badge" id="res-badge">720×1520</div>
      <div class="badge potato" id="potato-badge" style="display:none;">⚡ POTATO MODE ON</div>
    </div>

    <div class="controls">
      <button id="btn-potato" title="Ultra-lightweight rendering for vintage/weak laptops">⚡ Potato Mode</button>
      <button id="btn-rotate" title="Rotate Screen (Key: R)">↻ Rotate</button>
      <button id="btn-snap" title="Take Screenshot (Key: S)">📸 Screenshot</button>
      <button id="btn-fs" title="Fullscreen (Key: F)">⛶ Fullscreen</button>
    </div>
  </header>

  <main id="player-container">
    <div class="screen-wrapper" id="wrapper">
      <img id="stream-img" src="/stream" alt="Connecting to ScreenCraft USB Mirror..." />
    </div>
  </main>

  <footer>
    <div>Connected over USB • Low Latency Direct Pipeline</div>
    <div class="shortcuts">
      Shortcuts: <span>F</span> Fullscreen &nbsp; <span>R</span> Rotate &nbsp; <span>S</span> Screenshot
    </div>
  </footer>

<script>
  let rotation = 0;
  let isPotato = false;
  const img = document.getElementById('stream-img');
  const wrapper = document.getElementById('wrapper');
  const btnFs = document.getElementById('btn-fs');
  const btnRotate = document.getElementById('btn-rotate');
  const btnSnap = document.getElementById('btn-snap');
  const btnPotato = document.getElementById('btn-potato');
  const potatoBadge = document.getElementById('potato-badge');
  const fpsBadge = document.getElementById('fps-badge');
  const resBadge = document.getElementById('res-badge');

  // Rotate screen
  function rotateScreen() {
    rotation = (rotation + 90) % 360;
    wrapper.style.transform = 'rotate(' + rotation + 'deg)';
  }
  btnRotate.addEventListener('click', rotateScreen);

  // Fullscreen
  function toggleFullScreen() {
    if (!document.fullscreenElement) {
      document.documentElement.requestFullscreen().catch(() => {});
    } else {
      document.exitFullscreen().catch(() => {});
    }
  }
  btnFs.addEventListener('click', toggleFullScreen);

  // Screenshot
  btnSnap.addEventListener('click', () => {
    const a = document.createElement('a');
    a.href = '/snapshot?t=' + Date.now();
    a.download = 'ScreenCraft_' + Date.now() + '.jpg';
    a.click();
  });

  // Potato PC Mode Toggle
  btnPotato.addEventListener('click', () => {
    isPotato = !isPotato;
    document.body.classList.toggle('potato-mode', isPotato);
    btnPotato.classList.toggle('active', isPotato);
    potatoBadge.style.display = isPotato ? 'inline-flex' : 'none';
  });

  // Keyboard Shortcuts
  window.addEventListener('keydown', (e) => {
    if (e.key === 'f' || e.key === 'F') toggleFullScreen();
    if (e.key === 'r' || e.key === 'R') rotateScreen();
    if (e.key === 's' || e.key === 'S') btnSnap.click();
  });

  // Telemetry Poll
  setInterval(() => {
    fetch('/status')
      .then(res => res.json())
      .then(data => {
        if (data.fps) fpsBadge.textContent = 'FPS: ' + data.fps;
        if (data.width && data.height) resBadge.textContent = data.width + '×' + data.height;
      })
      .catch(() => {});
  }, 1000);

  // Auto-reconnect if connection blips
  img.onerror = () => {
    setTimeout(() => {
      img.src = '/stream?t=' + Date.now();
    }, 1000);
  };
</script>
</body>
</html>"""
}
