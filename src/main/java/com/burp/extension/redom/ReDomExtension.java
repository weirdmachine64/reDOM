package com.burp.extension.redom;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import com.burp.extension.redom.cdp.ChromeDevToolsClient;
import com.burp.extension.redom.handler.DomRenderHttpHandler;
import com.burp.extension.redom.ui.DomRenderTabProvider;
import com.burp.extension.redom.ui.SettingsPanel;

/**
 * Main extension class for reDOM - DOM Renderer Extension for Burp Suite.
 * 
 * This extension captures HTTP responses after DOM rendering by:
 * 1. Adding a custom "DOM Render" tab to the HTTP message editor
 * 2. Connecting to Chrome via Chrome DevTools Protocol (CDP)
 * 3. Replaying requests in the browser to capture post-DOM content
 * 4. Displaying the rendered HTML in the custom tab
 * 
 * Architecture:
 * - ChromeDevToolsClient: Manages WebSocket connection to Chrome
 * - PageRenderer: Orchestrates page rendering and DOM extraction
 * - DomRenderTab: Custom UI tab for displaying rendered content
 * - SettingsPanel: Configuration UI for connection and preferences
 * - DomRenderHttpHandler: Reserved for future request/response interception
 * 
 * Lifecycle:
 * - initialize(): Sets up all components and registers UI providers
 * - unload handler: Cleans up resources (timers, connections) on extension unload
 * 
 * @see ChromeDevToolsClient for CDP connection details
 * @see PageRenderer for rendering implementation
 */
public class ReDomExtension implements BurpExtension {

    private ChromeDevToolsClient cdpClient;
    private DomRenderHttpHandler httpHandler;
    private SettingsPanel settingsPanel;

    /**
     * Initialize the reDOM extension.
     * 
     * This method is called by Burp Suite when the extension is loaded.
     * It performs the following initialization steps:
     * 1. Sets the extension name in Burp's UI
     * 2. Creates the ChromeDevToolsClient for CDP communication
     * 3. Registers the settings panel in Burp's Suite tab
     * 4. Registers the HTTP handler (currently unused, reserved for future features)
     * 5. Registers the custom DOM Render tab provider
     * 6. Sets up cleanup on extension unload
     * 
     * Error Handling: Catches and logs any initialization errors to prevent
     * Burp Suite from failing to load other extensions.
     * 
     * @param api the Montoya API provided by Burp Suite
     */
    @Override
    public void initialize(MontoyaApi api) {
        // Set extension name
        api.extension().setName("reDOM");
        
        // Log initialization
        api.logging().logToOutput("");
        api.logging().logToOutput("  ██████╗ ███████╗██████╗  ██████╗ ███╗   ███╗");
        api.logging().logToOutput("  ██╔══██╗██╔════╝██╔══██╗██╔═══██╗████╗ ████║");
        api.logging().logToOutput("  ██████╔╝█████╗  ██║  ██║██║   ██║██╔████╔██║");
        api.logging().logToOutput("  ██╔══██╗██╔══╝  ██║  ██║██║   ██║██║╚██╔╝██║");
        api.logging().logToOutput("  ██║  ██║███████╗██████╔╝╚██████╔╝██║ ╚═╝ ██║");
        api.logging().logToOutput("  ╚═╝  ╚═╝╚══════╝╚═════╝  ╚═════╝ ╚═╝     ╚═╝");
        api.logging().logToOutput("");
        
        try {
            // Initialize CDP client
            cdpClient = new ChromeDevToolsClient();
            api.logging().logToOutput("Chrome DevTools Protocol client initialized");
            
            // Create and register settings panel
            settingsPanel = new SettingsPanel(cdpClient, api);
            api.userInterface().registerSuiteTab("reDOM Settings", settingsPanel);
            api.logging().logToOutput("Settings panel registered");
            
            // Register HTTP handler for request interception
            httpHandler = new DomRenderHttpHandler(cdpClient, api);
            api.http().registerHttpHandler(httpHandler);
            api.logging().logToOutput("HTTP handler registered");
            
            // Register custom tab provider
            DomRenderTabProvider tabProvider = new DomRenderTabProvider(api, settingsPanel);
            api.userInterface().registerHttpResponseEditorProvider(tabProvider);
            api.logging().logToOutput("reDOM tab provider registered");
            
            // Log success
            api.logging().logToOutput("========================================");
            api.logging().logToOutput("reDOM Extension loaded successfully!");
            api.logging().logToOutput("========================================");
            
        } catch (Exception e) {
            api.logging().logToError("Failed to initialize reDOM extension: " + e.getMessage());
            e.printStackTrace();
        }
        
        // Register unload handler to clean up resources
        api.extension().registerUnloadingHandler(() -> {
            api.logging().logToOutput("reDOM Extension unloading...");
            if (cdpClient != null && cdpClient.isConnected()) {
                cdpClient.disconnect();
                api.logging().logToOutput("Disconnected from Chrome");
            }
            api.logging().logToOutput("reDOM Extension unloaded");
        });
    }
}
