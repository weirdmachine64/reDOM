package com.burp.extension.redom;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import com.burp.extension.redom.cdp.ChromeDevToolsClient;
import com.burp.extension.redom.ui.DomRenderTabProvider;
import com.burp.extension.redom.ui.SettingsPanel;

public class ReDomExtension implements BurpExtension {

    private ChromeDevToolsClient cdpClient;
    private SettingsPanel settingsPanel;

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
            
            // Create and register settings panel in Burp's native Settings dialog
            settingsPanel = new SettingsPanel(cdpClient, api);
            api.userInterface().registerSettingsPanel(settingsPanel);
            api.userInterface().openSettingsWindow();
            api.logging().logToOutput("Settings panel registered");

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
        
        api.extension().registerUnloadingHandler(() -> {
            if (cdpClient != null && cdpClient.isConnected()) {
                cdpClient.disconnect();
            }
            Process chromiumProcess = settingsPanel != null ? settingsPanel.getChromiumProcess() : null;
            if (chromiumProcess != null && chromiumProcess.isAlive()) {
                chromiumProcess.destroy();
            }
        });
    }
}
