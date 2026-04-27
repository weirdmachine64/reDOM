package com.burp.extension.redom.cdp;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import burp.api.montoya.http.message.responses.HttpResponse;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class ChromeDevToolsClient {

    private static final String DEFAULT_CHROME_HOST = "localhost";
    private static final int DEFAULT_CHROME_PORT = 9222;
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    private final Gson gson = new Gson();
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(java.time.Duration.ofSeconds(10))
        .build();

    private String chromeHost = DEFAULT_CHROME_HOST;
    private int chromePort = DEFAULT_CHROME_PORT;
    private int timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
    private boolean connected = false;

    // ==================== Connection lifecycle ====================

    public void connect() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create("http://" + chromeHost + ":" + chromePort + "/json/version"))
            .GET()
            .build();
        java.net.http.HttpResponse<String> resp = httpClient.send(req,
            java.net.http.HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new Exception("Chrome not reachable at " + chromeHost + ":" + chromePort);
        }
        connected = true;
    }

    public void disconnect() {
        connected = false;
    }

    public boolean isConnected() {
        return connected;
    }

    public String getConnectionStatus() {
        return connected ? "Connected to Chrome on " + chromeHost + ":" + chromePort : "Not connected";
    }

    public void waitForChrome(int waitTimeoutSeconds) throws Exception {
        long deadline = System.currentTimeMillis() + waitTimeoutSeconds * 1000L;
        Exception last = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("http://" + chromeHost + ":" + chromePort + "/json/version"))
                    .GET()
                    .build();
                if (httpClient.send(req, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode() == 200) {
                    connected = true;
                    return;
                }
            } catch (Exception e) {
                last = e;
            }
            TimeUnit.MILLISECONDS.sleep(300);
        }
        throw new Exception("Chrome debug port not ready after " + waitTimeoutSeconds + "s" +
            (last != null ? ": " + last.getMessage() : ""));
    }

    // ==================== Rendering ====================

    public String renderPage(String url, HttpResponse burpResponse,
                             int pageLoadTimeoutSeconds, int renderDelayMs) throws Exception {
        try (TabSession tab = new TabSession()) {
            tab.setResponseOverride(url, burpResponse);
            tab.enableNetworkInterception();
            CompletableFuture<Void> loaded = tab.navigateTo(url);
            try {
                loaded.get(pageLoadTimeoutSeconds, TimeUnit.SECONDS);
            } catch (Exception ignored) {}
            if (renderDelayMs > 0) {
                TimeUnit.MILLISECONDS.sleep(renderDelayMs);
            }
            return tab.getDocumentHTML();
        }
    }

    // ==================== Configuration ====================

    public void setChromeHost(String host) { this.chromeHost = host; }
    public String getChromeHost() { return chromeHost; }
    public void setChromePort(int port) { this.chromePort = port; }
    public int getChromePort() { return chromePort; }
    public void setTimeout(int seconds) { this.timeoutSeconds = seconds; }
    public int getTimeout() { return timeoutSeconds; }

    // ==================== Per-render tab session ====================

    private class TabSession implements AutoCloseable {

        private final String tabId;
        private final WebSocketClient ws;
        private final Map<Integer, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();
        private final Map<String, HttpResponse> overrides = new ConcurrentHashMap<>();
        private final AtomicInteger msgId = new AtomicInteger(1);
        private volatile CompletableFuture<Void> loadFuture;

        TabSession() throws Exception {
            // Create a background tab so Chrome does not steal focus from Burp
            java.net.http.HttpResponse<String> versionResp = httpClient.send(
                HttpRequest.newBuilder()
                    .uri(URI.create("http://" + chromeHost + ":" + chromePort + "/json/version"))
                    .GET().build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
            String browserWsUrl = gson.fromJson(versionResp.body(), JsonObject.class)
                .get("webSocketDebuggerUrl").getAsString();
            tabId = createBackgroundTab(browserWsUrl);
            String wsUrl = "ws://" + chromeHost + ":" + chromePort + "/devtools/page/" + tabId;

            ws = new WebSocketClient(new URI(wsUrl)) {
                @Override public void onOpen(ServerHandshake h) {}
                @Override public void onMessage(String msg) { handleMessage(msg); }
                @Override public void onClose(int code, String reason, boolean remote) {}
                @Override public void onError(Exception ex) {}
            };
            ws.connectBlocking();
            enableDomains();
        }

        void setResponseOverride(String url, HttpResponse response) {
            overrides.put(url, response);
        }

        void enableNetworkInterception() throws Exception {
            JsonObject patterns = new JsonObject();
            JsonArray patternArray = new JsonArray();
            JsonObject pattern = new JsonObject();
            pattern.addProperty("urlPattern", "*");
            patternArray.add(pattern);
            patterns.add("patterns", patternArray);
            sendCommand("Fetch.enable", patterns).get(timeoutSeconds, TimeUnit.SECONDS);
        }

        CompletableFuture<Void> navigateTo(String url) throws Exception {
            loadFuture = new CompletableFuture<>();
            JsonObject params = new JsonObject();
            params.addProperty("url", url);
            sendCommand("Page.navigate", params).get(timeoutSeconds, TimeUnit.SECONDS);
            return loadFuture;
        }

        String getDocumentHTML() throws Exception {
            JsonObject docResult = sendCommand("DOM.getDocument", new JsonObject())
                .get(timeoutSeconds, TimeUnit.SECONDS);
            int nodeId = docResult.getAsJsonObject("root").get("nodeId").getAsInt();
            JsonObject params = new JsonObject();
            params.addProperty("nodeId", nodeId);
            JsonObject htmlResult = sendCommand("DOM.getOuterHTML", params)
                .get(timeoutSeconds, TimeUnit.SECONDS);
            return htmlResult.get("outerHTML").getAsString();
        }

        private void enableDomains() throws Exception {
            sendCommand("Page.enable", new JsonObject()).get(timeoutSeconds, TimeUnit.SECONDS);
            sendCommand("DOM.enable", new JsonObject()).get(timeoutSeconds, TimeUnit.SECONDS);
            sendCommand("Network.enable", new JsonObject()).get(timeoutSeconds, TimeUnit.SECONDS);
            sendCommand("Runtime.enable", new JsonObject()).get(timeoutSeconds, TimeUnit.SECONDS);
        }

        private CompletableFuture<JsonObject> sendCommand(String method, JsonObject params) {
            int id = msgId.getAndIncrement();
            CompletableFuture<JsonObject> future = new CompletableFuture<>();
            pending.put(id, future);
            JsonObject msg = new JsonObject();
            msg.addProperty("id", id);
            msg.addProperty("method", method);
            msg.add("params", params);
            ws.send(gson.toJson(msg));
            return future;
        }

        private void handleMessage(String message) {
            try {
                JsonObject json = gson.fromJson(message, JsonObject.class);
                if (json.has("id")) {
                    int id = json.get("id").getAsInt();
                    CompletableFuture<JsonObject> future = pending.remove(id);
                    if (future != null) {
                        if (json.has("error")) {
                            future.completeExceptionally(new Exception(
                                "CDP: " + json.getAsJsonObject("error").get("message").getAsString()));
                        } else {
                            future.complete(json.has("result") ? json.getAsJsonObject("result") : new JsonObject());
                        }
                    }
                } else if (json.has("method")) {
                    String method = json.get("method").getAsString();
                    if ("Page.loadEventFired".equals(method) && loadFuture != null && !loadFuture.isDone()) {
                        loadFuture.complete(null);
                    } else if ("Fetch.requestPaused".equals(method)) {
                        handleRequestPaused(json.getAsJsonObject("params"));
                    }
                }
            } catch (Exception ignored) {}
        }

        private void handleRequestPaused(JsonObject params) {
            try {
                String requestId = params.get("requestId").getAsString();
                String url = params.getAsJsonObject("request").get("url").getAsString();
                HttpResponse override = overrides.get(url);
                if (override != null) {
                    JsonArray headers = new JsonArray();
                    override.headers().forEach(h -> {
                        if (!"content-disposition".equalsIgnoreCase(h.name())) {
                            JsonObject hObj = new JsonObject();
                            hObj.addProperty("name", h.name());
                            hObj.addProperty("value", h.value());
                            headers.add(hObj);
                        }
                    });
                    String base64Body = Base64.getEncoder().encodeToString(override.body().getBytes());
                    JsonObject fulfillParams = new JsonObject();
                    fulfillParams.addProperty("requestId", requestId);
                    fulfillParams.addProperty("responseCode", override.statusCode());
                    fulfillParams.add("responseHeaders", headers);
                    fulfillParams.addProperty("body", base64Body);
                    sendCommand("Fetch.fulfillRequest", fulfillParams);
                } else {
                    JsonObject continueParams = new JsonObject();
                    continueParams.addProperty("requestId", requestId);
                    sendCommand("Fetch.continueRequest", continueParams);
                }
            } catch (Exception ignored) {}
        }

        private String createBackgroundTab(String browserWsUrl) throws Exception {
            CompletableFuture<String> result = new CompletableFuture<>();
            AtomicInteger cmdId = new AtomicInteger(1);
            WebSocketClient browserWs = new WebSocketClient(new URI(browserWsUrl)) {
                @Override public void onOpen(ServerHandshake h) {
                    JsonObject msg = new JsonObject();
                    msg.addProperty("id", cmdId.get());
                    msg.addProperty("method", "Target.createTarget");
                    JsonObject params = new JsonObject();
                    params.addProperty("url", "about:blank");
                    params.addProperty("background", true);
                    msg.add("params", params);
                    send(gson.toJson(msg));
                }
                @Override public void onMessage(String msg) {
                    JsonObject json = gson.fromJson(msg, JsonObject.class);
                    if (json.has("id") && json.get("id").getAsInt() == cmdId.get()) {
                        if (json.has("result")) {
                            result.complete(json.getAsJsonObject("result").get("targetId").getAsString());
                        } else {
                            result.completeExceptionally(new Exception("Target.createTarget failed"));
                        }
                    }
                }
                @Override public void onClose(int code, String reason, boolean remote) {
                    if (!result.isDone()) result.completeExceptionally(new Exception("Browser WS closed early"));
                }
                @Override public void onError(Exception ex) { result.completeExceptionally(ex); }
            };
            browserWs.connectBlocking();
            try {
                return result.get(timeoutSeconds, TimeUnit.SECONDS);
            } finally {
                browserWs.close();
            }
        }

        @Override
        public void close() {
            try { ws.close(); } catch (Exception ignored) {}
            try {
                httpClient.send(
                    HttpRequest.newBuilder()
                        .uri(URI.create("http://" + chromeHost + ":" + chromePort + "/json/close/" + tabId))
                        .GET()
                        .build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            } catch (Exception ignored) {}
        }
    }
}
