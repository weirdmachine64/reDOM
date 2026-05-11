package com.burp.extension.redom.cdp;

/**
 * Handles page rendering using Chrome DevTools Protocol to inject Burp responses.
 *
 * This class orchestrates:
 * - Network interception to override responses with Burp data
 * - Page navigation and JavaScript execution wait time
 * - DOM extraction after rendering completes
 *
 * Performance: Uses configurable JavaScript execution wait time (default 1000ms)
 * to balance between page load completion and rendering speed.
 */
public class PageRenderer {

    private static final int DEFAULT_PAGE_LOAD_TIMEOUT_SECONDS = 30;
    private static final int DEFAULT_RENDER_DELAY_MS = 1000; // Wait time after page load for JavaScript rendering

    /**
     * Response data containing rendered HTML.
     */
    public static class ResponseData {
        private final String renderedHtml;

        public ResponseData(String renderedHtml) {
            this.renderedHtml = renderedHtml;
        }

        public String getRenderedHtml() {
            return renderedHtml;
        }
    }
    
    private final ChromeDevToolsClient cdpClient;
    private final burp.api.montoya.MontoyaApi api;
    private boolean followRedirects = false;
    private boolean showPrettyTab = true;
    private int renderDelayMs = DEFAULT_RENDER_DELAY_MS; // Configurable delay after page load
    private int pageLoadTimeoutSeconds = DEFAULT_PAGE_LOAD_TIMEOUT_SECONDS; // Configurable page load timeout
    
    public PageRenderer(ChromeDevToolsClient cdpClient, burp.api.montoya.MontoyaApi api) {
        this.cdpClient = cdpClient;
        this.api = api;
    }
    
    /**
     * Configure whether to follow HTTP redirects during rendering.
     * Currently not implemented - redirect responses are returned as-is.
     * 
     * @param followRedirects true to follow redirects, false to return redirect response
     */
    public void setFollowRedirects(boolean followRedirects) {
        this.followRedirects = followRedirects;
    }
    
    /**
     * Check if redirect following is enabled.
     * 
     * @return true if redirects should be followed, false otherwise
     */
    public boolean isFollowRedirects() {
        return followRedirects;
    }
    
    /**
     * Set whether to display the Pretty tab (syntax highlighted) or Raw tab.
     * 
     * @param showPrettyTab true for Pretty tab, false for Raw tab
     */
    public void setShowPrettyTab(boolean showPrettyTab) {
        this.showPrettyTab = showPrettyTab;
    }
    
    /**
     * Check if Pretty tab should be shown.
     * 
     * @return true if Pretty tab is preferred, false for Raw tab
     */
    public boolean isShowPrettyTab() {
        return showPrettyTab;
    }
    
    /**
     * Set the delay after page load to wait for JavaScript rendering.
     * 
     * @param delayMs delay in milliseconds (default: 1000)
     */
    public void setRenderDelay(int delayMs) {
        this.renderDelayMs = delayMs;
    }
    
    /**
     * Get the current render delay setting.
     * 
     * @return delay in milliseconds
     */
    public int getRenderDelay() {
        return renderDelayMs;
    }
    
    /**
     * Set the timeout for page load operations.
     * 
     * @param seconds timeout in seconds (default: 30)
     */
    public void setPageLoadTimeout(int seconds) {
        this.pageLoadTimeoutSeconds = seconds;
    }
    
    /**
     * Get the current page load timeout setting.
     * 
     * @return timeout in seconds
     */
    public int getPageLoadTimeout() {
        return pageLoadTimeoutSeconds;
    }
    
    /**
     * Render a page from a Burp request/response by intercepting and overriding the response.
     * 
     * Process:
     * 1. Check for redirect responses (3xx) and return early if detected
     * 2. Enable network interception in Chrome
     * 3. Set response override for the target URL
     * 4. Navigate Chrome to the URL (triggers interception)
     * 5. Wait for page load and JavaScript execution
     * 6. Extract rendered DOM HTML
     * 7. Clean up interception state
     * 
     * Performance: Includes configurable JavaScript wait time (default 1000ms)
     * to allow dynamic content to render before DOM extraction.
     * 
     * @param request Burp HTTP request to replay
     * @param response Burp HTTP response to inject into Chrome
     * @return ResponseData containing rendered HTML and metadata
     * @throws Exception if rendering fails or times out
     */
    public ResponseData render(burp.api.montoya.http.message.requests.HttpRequest request, 
                              burp.api.montoya.http.message.responses.HttpResponse response) throws Exception {
        String url = request.url();
        
        // Check if this is a redirect response (3xx status code)
        int statusCode = response.statusCode();
        boolean isRedirect = statusCode >= 300 && statusCode < 400;
        
        // If redirect is detected, return early without rendering
        if (isRedirect) {
            return new ResponseData(response.bodyToString());
        }

        // If the response is not HTML, return original body without rendering
        String contentType = "";
        for (burp.api.montoya.http.message.HttpHeader h : response.headers()) {
            if ("Content-Type".equalsIgnoreCase(h.name())) {
                contentType = h.value();
                break;
            }
        }
        if (contentType != null && !contentType.isEmpty() && !contentType.toLowerCase().contains("html")) {
            return new ResponseData(response.bodyToString());
        }

        String renderedHtml = trimLeadingNewlines(
            cdpClient.renderPage(url, response, pageLoadTimeoutSeconds, renderDelayMs));

        return new ResponseData(renderedHtml);
    }
    
    // ==================== Utility Methods ====================
    
    /**
     * Trim leading newlines from HTML for cleaner display.
     * 
     * @param html the HTML string to trim
     * @return trimmed HTML string
     */
    private String trimLeadingNewlines(String html) {
        while (html.startsWith("\n") || html.startsWith("\r")) {
            html = html.substring(1);
        }
        return html;
    }
    
}
