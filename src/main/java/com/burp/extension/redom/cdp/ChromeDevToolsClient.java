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

/**
 * Chrome DevTools Protocol (CDP) client for WebSocket communication with Chrome.
 * 
 * This class manages:
 * - Connection lifecycle and auto-reconnection
 * - Network request interception and response overriding
 * - Page navigation and DOM manipulation
 * - Window management (minimize, create, close)
 * 
 * Thread Safety: This class uses concurrent collections and atomic operations
 * for thread-safe communication with Chrome.
 */
public class ChromeDevToolsClient {
    
    private static final String DEFAULT_CHROME_HOST = "localhost";
    private static final int DEFAULT_CHROME_PORT = 9222;
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    
    private final Gson gson = new Gson();
    private final AtomicInteger messageId = new AtomicInteger(1);
    private final Map<Integer, CompletableFuture<JsonObject>> pendingRequests = new ConcurrentHashMap<>();
    private final Map<String, HttpResponse> responseOverrides = new ConcurrentHashMap<>();
    // Reusable HTTP client instance for better performance (avoids creating new instances)
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(java.time.Duration.ofSeconds(10))
        .build();
    
    private WebSocketClient webSocketClient;
    private boolean connected = false;
    private volatile CompletableFuture<Void> pageLoadFuture = null;
    private boolean networkInterceptionEnabled = false;
    private String managedTabId = null;  // Our dedicated tab ID
    private boolean autoReconnect = true;
    private boolean minimizeWindow = true;  // Minimize window on connect (default: enabled)
    
    // User-configurable parameters
    private String chromeHost = DEFAULT_CHROME_HOST;
    private int chromePort = DEFAULT_CHROME_PORT;
    private int timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;

    /**
     * Establishes connection to Chrome via WebSocket.
     * Creates a dedicated managed window for reDOM to isolate from user tabs.
     * 
     * Process:
     * 1. Get or create a dedicated Chrome window/tab
     * 2. Connect WebSocket client to the tab's debugger URL
     * 3. Enable required CDP domains (Page, DOM, Network, Runtime)
     * 4. Set identifying content in the tab
     * 5. Minimize the window if enabled
     * 
     * @throws Exception if Chrome is not running or connection fails
     */
    public void connect() throws Exception {
        if (connected) return;
        
        // Create a dedicated tab for reDOM or reuse existing one
        String wsUrl = getOrCreateManagedTab();
        
        webSocketClient = new WebSocketClient(new URI(wsUrl)) {
            @Override
            public void onOpen(ServerHandshake handshakedata) {
                connected = true;
            }

            @Override
            public void onMessage(String message) {
                handleMessage(message);
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                connected = false;
                handleDisconnection(remote);
            }

            @Override
            public void onError(Exception ex) {
                // WebSocket error - connection will be handled by onClose
            }
        };
        
        webSocketClient.connectBlocking();
        enableDomains();
        
        // Set content in the managed tab to identify it as reDOM's tab
        setReDomTabContent();
        
        // Minimize the reDOM window to keep it hidden (if enabled)
        if (minimizeWindow) {
            minimizeWindowInternal();
        }
    }

    /**
     * Gracefully disconnect from Chrome and close the managed tab.
     * Disables auto-reconnect to prevent immediate reconnection attempts.
     */
    public void disconnect() {
        autoReconnect = false;  // Disable auto-reconnect on intentional disconnect
        
        if (webSocketClient != null && connected) {
            webSocketClient.close();
            connected = false;
        }
        
        // Optionally close the managed tab
        closeManagedTab();
    }
    
    /**
     * Handle disconnection and attempt auto-recovery if enabled.
     * Uses exponential backoff to avoid overwhelming Chrome during reconnection.
     * 
     * @param remote true if disconnection was initiated by remote peer
     */
    private void handleDisconnection(boolean remote) {
        if (remote && autoReconnect) {
            // Tab was closed by user or Chrome - attempt to reconnect
            try {
                TimeUnit.MILLISECONDS.sleep(1000);  // Brief delay before reconnecting
                managedTabId = null;  // Force creation of new tab
                connect();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                // Auto-reconnect interrupted
            } catch (Exception e) {
                // Auto-reconnect failed - will require manual reconnection
            }
        }
    }
    
    /**
     * Close the managed tab when disconnecting.
     * Uses the cached HTTP client for better performance.
     */
    private void closeManagedTab() {
        if (managedTabId != null) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://" + chromeHost + ":" + chromePort + "/json/close/" + managedTabId))
                    .GET()
                    .build();
                httpClient.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
                managedTabId = null;
            } catch (Exception e) {
                // Tab close failed - not critical
            }
        }
    }

    /**
     * Check if currently connected to Chrome.
     * 
     * @return true if WebSocket is connected and open, false otherwise
     */
    public boolean isConnected() {
        return connected && webSocketClient != null && webSocketClient.isOpen();
    }
    
    /**
     * Get human-readable connection status string.
     * 
     * @return status message indicating connection state and port
     */
    public String getConnectionStatus() {
        return isConnected() ? "Connected to Chrome on " + chromeHost + ":" + chromePort : "Not connected";
    }
    
    /**
     * Set the Chrome debugging host.
     * Must be called before connect().
     * 
     * @param host Chrome host (e.g., "localhost" or IP address)
     */
    public void setChromeHost(String host) {
        this.chromeHost = host;
    }
    
    /**
     * Get the current Chrome debugging host.
     * 
     * @return Chrome host
     */
    public String getChromeHost() {
        return chromeHost;
    }
    
    /**
     * Set the Chrome debugging port.
     * Must be called before connect().
     * 
     * @param port Chrome debugging port (default: 9222)
     */
    public void setChromePort(int port) {
        this.chromePort = port;
    }
    
    /**
     * Get the current Chrome debugging port.
     * 
     * @return Chrome debugging port
     */
    public int getChromePort() {
        return chromePort;
    }
    
    /**
     * Set the timeout for CDP commands and page loads.
     * 
     * @param seconds timeout in seconds (default: 30)
     */
    public void setTimeout(int seconds) {
        this.timeoutSeconds = seconds;
    }
    
    /**
     * Get the current timeout setting.
     * 
     * @return timeout in seconds
     */
    public int getTimeout() {
        return timeoutSeconds;
    }
    
    // ==================== CDP Commands ====================
    
    /**
     * Navigate to a URL and return a future that completes when page loads.
     * 
     * Performance: Uses Page.loadEventFired event for completion detection.
     * 
     * @param url the URL to navigate to
     * @return CompletableFuture that completes when page load event fires
     * @throws Exception if navigation command fails
     */
    public CompletableFuture<Void> navigateAndWaitForLoad(String url) throws Exception {
        pageLoadFuture = new CompletableFuture<>();
        JsonObject params = new JsonObject();
        params.addProperty("url", url);
        sendCommand("Page.navigate", params).get(timeoutSeconds, TimeUnit.SECONDS);
        return pageLoadFuture;
    }
    
    /**
     * Navigate to about:blank to clear the current page.
     * Useful for resetting state between renders.
     * 
     * @throws Exception if navigation command fails
     */
    public void navigateToBlank() throws Exception {
        JsonObject params = new JsonObject();
        params.addProperty("url", "about:blank");
        sendCommand("Page.navigate", params).get(timeoutSeconds, TimeUnit.SECONDS);
    }
    
    /**
     * Evaluate JavaScript code in the page context.
     * 
     * @param code JavaScript code to execute
     * @throws Exception if evaluation fails
     */
    public void evaluateJavaScript(String code) throws Exception {
        JsonObject params = new JsonObject();
        params.addProperty("expression", code);
        sendCommand("Runtime.evaluate", params).get(timeoutSeconds, TimeUnit.SECONDS);
    }
    
    /**
     * Get the full document HTML including DOCTYPE and all elements.
     * 
     * Process:
     * 1. Get the document node via DOM.getDocument
     * 2. Extract outer HTML via DOM.getOuterHTML
     * 
     * @return complete HTML document as string
     * @throws Exception if DOM commands fail
     */
    public String getDocumentHTML() throws Exception {
        JsonObject docResult = sendCommand("DOM.getDocument", new JsonObject())
            .get(timeoutSeconds, TimeUnit.SECONDS);
        
        int nodeId = docResult.getAsJsonObject("root").get("nodeId").getAsInt();
        
        JsonObject params = new JsonObject();
        params.addProperty("nodeId", nodeId);
        
        JsonObject htmlResult = sendCommand("DOM.getOuterHTML", params)
            .get(timeoutSeconds, TimeUnit.SECONDS);
        
        return htmlResult.get("outerHTML").getAsString();
    }
    
    /**
     * Get the body text content (for non-HTML responses).
     */
    public String getBodyTextContent() throws Exception {
        JsonObject params = new JsonObject();
        params.addProperty("expression", "document.body.textContent");
        JsonObject result = sendCommand("Runtime.evaluate", params)
            .get(timeoutSeconds, TimeUnit.SECONDS);
        
        if (result.has("result") && result.getAsJsonObject("result").has("value")) {
            return result.getAsJsonObject("result").get("value").getAsString();
        }
        return "";
    }
    
    /**
     * Enable network request interception using Fetch domain.
     * Required for overriding responses with Burp data.
     * 
     * Performance: Only enables if not already enabled to avoid redundant CDP calls.
     * 
     * @throws Exception if Fetch.enable command fails
     */
    public void enableNetworkInterception() throws Exception {
        if (networkInterceptionEnabled) return;
        
        JsonObject patterns = new JsonObject();
        JsonArray patternArray = new JsonArray();
        JsonObject pattern = new JsonObject();
        pattern.addProperty("urlPattern", "*");
        patternArray.add(pattern);
        patterns.add("patterns", patternArray);
        
        sendCommand("Fetch.enable", patterns).get(timeoutSeconds, TimeUnit.SECONDS);
        networkInterceptionEnabled = true;
    }
    
    /**
     * Disable network request interception and clear response overrides.
     * Should be called after rendering completes to clean up state.
     * 
     * @throws Exception if Fetch.disable command fails
     */
    public void disableNetworkInterception() throws Exception {
        if (!networkInterceptionEnabled) return;
        
        sendCommand("Fetch.disable", new JsonObject()).get(timeoutSeconds, TimeUnit.SECONDS);
        networkInterceptionEnabled = false;
        responseOverrides.clear();
    }
    
    /**
     * Set a response override for a specific URL.
     * When Chrome requests this URL, the intercepted request will be fulfilled
     * with the provided Burp response instead of making a real network request.
     * 
     * Thread-safe: Uses ConcurrentHashMap for concurrent access.
     * 
     * @param url the URL to intercept
     * @param response the Burp response to return for this URL
     */
    public void setResponseOverride(String url, HttpResponse response) {
        responseOverrides.put(url, response);
    }
    
    // ==================== Internal Methods ====================
    
    /**
     * Get or create a dedicated managed tab for reDOM.
     * This ensures we don't depend on user tabs that might be closed.
     * Uses cached HttpClient for better performance.
     * 
     * @return WebSocket debugger URL for the managed tab
     * @throws Exception if Chrome is not accessible or tab creation fails
     */
    private String getOrCreateManagedTab() throws Exception {
        // First, check if our managed tab still exists
        if (managedTabId != null) {
            HttpRequest listRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://" + chromeHost + ":" + chromePort + "/json/list"))
                .GET()
                .build();
            
            java.net.http.HttpResponse<String> listResponse = httpClient.send(listRequest, 
                java.net.http.HttpResponse.BodyHandlers.ofString());
            
            if (listResponse.statusCode() == 200) {
                JsonArray tabs = gson.fromJson(listResponse.body(), JsonArray.class);
                
                // Check if our tab still exists
                for (int i = 0; i < tabs.size(); i++) {
                    JsonObject tab = tabs.get(i).getAsJsonObject();
                    if (tab.has("id") && managedTabId.equals(tab.get("id").getAsString())) {
                        return tab.get("webSocketDebuggerUrl").getAsString();
                    }
                }
                
                // Tab no longer exists, need to create a new one
                managedTabId = null;
            }
        }
        
        // Create a new dedicated window for reDOM using the browser-level endpoint
        // First, get the browser WebSocket endpoint
        HttpRequest versionRequest = HttpRequest.newBuilder()
            .uri(URI.create("http://" + chromeHost + ":" + chromePort + "/json/version"))
            .GET()
            .build();
        
        java.net.http.HttpResponse<String> versionResponse = httpClient.send(versionRequest, 
            java.net.http.HttpResponse.BodyHandlers.ofString());
        
        if (versionResponse.statusCode() != 200) {
            throw new Exception("Failed to connect to Chrome on " + chromeHost + ":" + chromePort + 
                ". Make sure Chrome is running with --remote-debugging-port=" + chromePort);
        }
        
        JsonObject versionInfo = gson.fromJson(versionResponse.body(), JsonObject.class);
        String browserWsUrl = versionInfo.get("webSocketDebuggerUrl").getAsString();
        
        // Connect to browser-level WebSocket to create a new window
        String targetId = createNewWindowTarget(browserWsUrl);
        
        // Now get the WebSocket URL for the newly created target
        HttpRequest listRequest = HttpRequest.newBuilder()
            .uri(URI.create("http://" + chromeHost + ":" + chromePort + "/json/list"))
            .GET()
            .build();
        
        java.net.http.HttpResponse<String> listResponse = httpClient.send(listRequest, 
            java.net.http.HttpResponse.BodyHandlers.ofString());
        
        if (listResponse.statusCode() == 200) {
            JsonArray tabs = gson.fromJson(listResponse.body(), JsonArray.class);
            
            for (int i = 0; i < tabs.size(); i++) {
                JsonObject tab = tabs.get(i).getAsJsonObject();
                if (tab.has("id") && targetId.equals(tab.get("id").getAsString())) {
                    managedTabId = targetId;
                    return tab.get("webSocketDebuggerUrl").getAsString();
                }
            }
        }
        
        throw new Exception("Failed to create new Chrome window for reDOM");
    }
    
    /**
     * Create a new window target using the browser-level WebSocket connection.
     */
    private String createNewWindowTarget(String browserWsUrl) throws Exception {
        final CompletableFuture<String> targetIdFuture = new CompletableFuture<>();
        final AtomicInteger cmdId = new AtomicInteger(1);
        
        WebSocketClient browserClient = new WebSocketClient(new URI(browserWsUrl)) {
            @Override
            public void onOpen(ServerHandshake handshakedata) {
                // Send Target.createTarget command with newWindow=true
                JsonObject message = new JsonObject();
                message.addProperty("id", cmdId.get());
                message.addProperty("method", "Target.createTarget");
                
                JsonObject params = new JsonObject();
                params.addProperty("url", "about:blank");
                params.addProperty("newWindow", true);
                message.add("params", params);
                
                send(gson.toJson(message));
            }

            @Override
            public void onMessage(String message) {
                try {
                    JsonObject json = gson.fromJson(message, JsonObject.class);
                    
                    if (json.has("id") && json.get("id").getAsInt() == cmdId.get()) {
                        if (json.has("result")) {
                            JsonObject result = json.getAsJsonObject("result");
                            String targetId = result.get("targetId").getAsString();
                            targetIdFuture.complete(targetId);
                        } else if (json.has("error")) {
                            targetIdFuture.completeExceptionally(
                                new Exception("Failed to create window: " + json.getAsJsonObject("error").get("message").getAsString())
                            );
                        }
                    }
                } catch (Exception e) {
                    targetIdFuture.completeExceptionally(e);
                }
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                if (!targetIdFuture.isDone()) {
                    targetIdFuture.completeExceptionally(new Exception("WebSocket closed before target created"));
                }
            }

            @Override
            public void onError(Exception ex) {
                targetIdFuture.completeExceptionally(ex);
            }
        };
        
        browserClient.connectBlocking();
        
        try {
            String targetId = targetIdFuture.get(timeoutSeconds, TimeUnit.SECONDS);
            return targetId;
        } finally {
            browserClient.close();
        }
    }
    
    /**
     * Set identifying content in the managed tab.
     */
    private void setReDomTabContent() {
        try {
            String html = loadResourceAsString("/redom-tab.html");
            
            String script = "document.open(); " +
                "document.write(" + gson.toJson(html) + "); " +
                "document.close();";
            
            evaluateJavaScript(script);
        } catch (Exception e) {
            // Setting tab content failed - not critical for functionality
        }
    }
    
    /**
     * Load a resource file as a string.
     */
    private String loadResourceAsString(String resourcePath) throws Exception {
        try (java.io.InputStream is = getClass().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new Exception("Resource not found: " + resourcePath);
            }
            return new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private void enableDomains() throws Exception {
        try {
            sendCommand("Page.enable", new JsonObject()).get(timeoutSeconds, TimeUnit.SECONDS);
            sendCommand("DOM.enable", new JsonObject()).get(timeoutSeconds, TimeUnit.SECONDS);
            sendCommand("Network.enable", new JsonObject()).get(timeoutSeconds, TimeUnit.SECONDS);
            sendCommand("Runtime.enable", new JsonObject()).get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            // CDP domains initialization failed - will be caught when commands are used
        }
    }
    
    /**
     * Set whether to minimize the reDOM window on connect.
     */
    public void setMinimizeWindow(boolean minimize) {
        this.minimizeWindow = minimize;
    }
    
    /**
     * Get whether window minimization is enabled.
     */
    public boolean isMinimizeWindow() {
        return minimizeWindow;
    }
    
    /**
     * Minimize the reDOM window to keep it hidden from the user.
     */
    private void minimizeWindowInternal() {
        try {
            // Get the window ID for our managed tab
            JsonObject getWindowParams = new JsonObject();
            getWindowParams.addProperty("targetId", managedTabId);
            
            CompletableFuture<JsonObject> windowFuture = sendCommand("Browser.getWindowForTarget", getWindowParams);
            JsonObject windowResult = windowFuture.get(timeoutSeconds, TimeUnit.SECONDS);
            
            if (windowResult.has("windowId")) {
                int windowId = windowResult.get("windowId").getAsInt();
                
                // Set window bounds to minimized state
                JsonObject setBoundsParams = new JsonObject();
                setBoundsParams.addProperty("windowId", windowId);
                
                JsonObject bounds = new JsonObject();
                bounds.addProperty("windowState", "minimized");
                setBoundsParams.add("bounds", bounds);
                
                sendCommand("Browser.setWindowBounds", setBoundsParams).get(timeoutSeconds, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            // Window minimization failed - not critical, continue anyway
        }
    }

    private CompletableFuture<JsonObject> sendCommand(String method, JsonObject params) {
        if (!isConnected()) {
            CompletableFuture<JsonObject> future = new CompletableFuture<>();
            future.completeExceptionally(new Exception(
                "Not connected to Chrome. Please go to 'reDOM Settings' tab and click 'Connect to Chrome'."));
            return future;
        }
        
        int id = messageId.getAndIncrement();
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        pendingRequests.put(id, future);
        
        JsonObject message = new JsonObject();
        message.addProperty("id", id);
        message.addProperty("method", method);
        message.add("params", params);
        
        webSocketClient.send(gson.toJson(message));
        return future;
    }

    private void handleMessage(String message) {
        try {
            JsonObject json = gson.fromJson(message, JsonObject.class);
            
            if (json.has("id")) {
                int id = json.get("id").getAsInt();
                CompletableFuture<JsonObject> future = pendingRequests.remove(id);
                
                if (future != null) {
                    if (json.has("error")) {
                        JsonObject error = json.getAsJsonObject("error");
                        future.completeExceptionally(
                            new Exception("CDP Error: " + error.get("message").getAsString())
                        );
                    } else {
                        future.complete(json.has("result") ? json.getAsJsonObject("result") : new JsonObject());
                    }
                }
            } else if (json.has("method")) {
                String method = json.get("method").getAsString();
                
                if ("Page.loadEventFired".equals(method)) {
                    if (pageLoadFuture != null && !pageLoadFuture.isDone()) {
                        pageLoadFuture.complete(null);
                    }
                } else if ("Fetch.requestPaused".equals(method)) {
                    handleRequestPaused(json.getAsJsonObject("params"));
                }
            }
        } catch (Exception e) {
            // Message handling error - will be caught by specific operations
        }
    }
    
    private void handleRequestPaused(JsonObject params) {
        try {
            String requestId = params.get("requestId").getAsString();
            JsonObject request = params.getAsJsonObject("request");
            String url = request.get("url").getAsString();
            
            // Check if we have an override for this URL
            HttpResponse override = responseOverrides.get(url);
            
            if (override != null) {
                
                // Build response headers, filtering out Content-Disposition to prevent downloads
                JsonArray headers = new JsonArray();
                override.headers().forEach(header -> {
                    String headerName = header.name();
                    // Skip Content-Disposition header to prevent Chrome from downloading the response
                    if (!"content-disposition".equalsIgnoreCase(headerName)) {
                        JsonObject headerObj = new JsonObject();
                        headerObj.addProperty("name", headerName);
                        headerObj.addProperty("value", header.value());
                        headers.add(headerObj);
                    }
                });
                
                // Encode body as base64 - use raw bytes to preserve encoding
                byte[] bodyBytes = override.body().getBytes();
                String base64Body = Base64.getEncoder().encodeToString(bodyBytes);
                
                JsonObject fulfillParams = new JsonObject();
                fulfillParams.addProperty("requestId", requestId);
                fulfillParams.addProperty("responseCode", override.statusCode());
                fulfillParams.add("responseHeaders", headers);
                fulfillParams.addProperty("body", base64Body);
                
                sendCommand("Fetch.fulfillRequest", fulfillParams);
            } else {
                // No override, continue with normal request
                JsonObject continueParams = new JsonObject();
                continueParams.addProperty("requestId", requestId);
                sendCommand("Fetch.continueRequest", continueParams);
            }
        } catch (Exception e) {
            // Error handling intercepted request - will fail silently
        }
    }
}
