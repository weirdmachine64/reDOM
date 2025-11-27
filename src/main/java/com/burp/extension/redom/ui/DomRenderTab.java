package com.burp.extension.redom.ui;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.ui.Selection;
import burp.api.montoya.ui.editor.extension.EditorCreationContext;
import burp.api.montoya.ui.editor.extension.ExtensionProvidedHttpResponseEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;
import com.burp.extension.redom.cdp.ChromeDevToolsClient;

import com.burp.extension.redom.cdp.PageRenderer;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

/**
 * Custom tab that displays HTML content after DOM rendering using Burp's built-in HTTP response editor.
 * This tab appears alongside other tabs in the HTTP response editor (next to Render, Pretty, etc.).
 * 
 * Performance optimizations:
 * - Uses SwingWorker for background rendering to keep UI responsive
 * - Caches rendered content to avoid re-rendering on tab switches
 * - Reuses Burp's built-in editor for syntax highlighting
 */
public class DomRenderTab implements ExtensionProvidedHttpResponseEditor {
    
    // UI Constants
    private static final String TAB_CAPTION = "reDOM";
    private static final String BTN_RENDER = "Render in Browser";
    private static final String TAB_PRETTY = "Pretty";
    private static final String TAB_RAW = "Raw";
    private static final String MSG_LOADING = "Loading response, this may take a moment...";
    private static final String MSG_NO_REQUEST = "No request to render";
    private static final String MSG_NO_RESPONSE = "No response available - send the request first";
    private static final String MSG_NOT_CONNECTED = "Chrome is not connected.\nGo to 'reDOM Settings' tab and click 'Connect to Chrome'.";
    private static final String TITLE_NOT_CONNECTED = "Chrome Not Connected";
    
    private final MontoyaApi api;
    private final SettingsPanel settingsPanel;
    private final JPanel panel;
    private final HttpResponseEditor responseEditor;
    private final JButton renderButton;
    private final JLabel statusLabel;
    
    private HttpRequestResponse currentRequestResponse;
    private String renderedHtml = "";
    private boolean hasRendered = false;

    public DomRenderTab(EditorCreationContext creationContext, MontoyaApi api, SettingsPanel settingsPanel) {
        this.api = api;
        this.settingsPanel = settingsPanel;
        
        // Create Burp's built-in HTTP response editor with pretty printing
        responseEditor = api.userInterface().createHttpResponseEditor();
        
        // Create UI components
        panel = new JPanel(new BorderLayout());
        
        // Top panel with render button and status (only shown when auto-render is disabled)
        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        renderButton = new JButton(BTN_RENDER);
        statusLabel = new JLabel("");
        statusLabel.setForeground(Color.BLUE);
        
        topPanel.add(renderButton);
        topPanel.add(statusLabel);
        
        // Add components to panel - toolbar visibility managed in setRequestResponse
        panel.add(topPanel, BorderLayout.NORTH);
        
        // Hide nested tabs in the response editor
        Component editorComponent = responseEditor.uiComponent();
        hideTabbedPane(editorComponent);
        panel.add(editorComponent, BorderLayout.CENTER);
        
        // Add button listener
        renderButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                renderInBrowser();
            }
        });
    }

    /**
     * Render the current request in the browser and fetch DOM content.
     * Uses SwingWorker for background execution to prevent UI blocking.
     */
    private void renderInBrowser() {
        if (currentRequestResponse == null || currentRequestResponse.request() == null) {
            showStatus(MSG_NO_REQUEST, Color.RED);
            return;
        }
        
        if (currentRequestResponse.response() == null) {
            showStatus(MSG_NO_RESPONSE, Color.RED);
            return;
        }
        
        // Check if Chrome is connected
        if (!checkChromeConnection()) {
            return;
        }
        
        renderButton.setEnabled(false);
        
        // Show loading message in editor
        responseEditor.setResponse(HttpResponse.httpResponse(MSG_LOADING));
        
        // Run in background thread
        SwingWorker<PageRenderer.ResponseData, Void> worker = new SwingWorker<PageRenderer.ResponseData, Void>() {
            @Override
            protected PageRenderer.ResponseData doInBackground() throws Exception {
                HttpRequest request = currentRequestResponse.request();
                HttpResponse response = currentRequestResponse.response();
                return settingsPanel.getPageRenderer().render(request, response);
            }

            @Override
            protected void done() {
                try {
                    PageRenderer.ResponseData responseData = get();
                    renderedHtml = responseData.getRenderedHtml();
                    
                    // Create new response with rendered HTML body as UTF-8 bytes, preserving original status, headers, and HTTP version
                    HttpResponse originalResponse = currentRequestResponse.response();
                    HttpResponse newResponse = originalResponse.withBody(burp.api.montoya.core.ByteArray.byteArray(renderedHtml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                    responseEditor.setResponse(newResponse);
                    
                    // Select the appropriate tab based on settings
                    selectEditorTab();
                    
                    hasRendered = true;
                    showStatus("Rendered successfully (" + renderedHtml.length() + " bytes)", Color.GREEN.darker());
                } catch (Exception ex) {                    
                    String errorResponse = "Error rendering page:\n\n" + ex.getMessage();
                    
                    responseEditor.setResponse(HttpResponse.httpResponse(errorResponse));
                    showStatus("Error: " + ex.getMessage(), Color.RED);
                    ex.printStackTrace();
                } finally {
                    renderButton.setEnabled(true);
                }
            }
        };
        
        worker.execute();
    }

    /**
     * Update status label.
     */
    private void showStatus(String message, Color color) {
        statusLabel.setText(message);
        statusLabel.setForeground(color);
    }
    
    /**
     * Select the appropriate tab (Pretty or Raw) in the response editor based on settings.
     */
    private void selectEditorTab() {
        boolean showPretty = settingsPanel.getPageRenderer().isShowPrettyTab();
        Component editorComponent = responseEditor.uiComponent();
        selectTabRecursively(editorComponent, showPretty ? TAB_PRETTY : TAB_RAW);
    }
    
    /**
     * Recursively search for JTabbedPane and select the specified tab.
     * Optimized with early return once tab is found.
     * 
     * @param component root component to search from
     * @param tabName name of tab to select
     * @return true if tab was found and selected, false otherwise
     */
    private boolean selectTabRecursively(Component component, String tabName) {
        if (component instanceof JTabbedPane) {
            JTabbedPane tabbedPane = (JTabbedPane) component;
            for (int i = 0; i < tabbedPane.getTabCount(); i++) {
                if (tabName.equalsIgnoreCase(tabbedPane.getTitleAt(i))) {
                    tabbedPane.setSelectedIndex(i);
                    return true;
                }
            }
        } else if (component instanceof Container) {
            Container container = (Container) component;
            for (Component child : container.getComponents()) {
                if (selectTabRecursively(child, tabName)) {
                    return true; // Early return once found
                }
            }
        }
        return false;
    }
    
    /**
     * Check if Chrome is connected and show popup if not.
     * @return true if connected, false otherwise
     */
    private boolean checkChromeConnection() {
        if (!settingsPanel.getCdpClient().isConnected()) {
            JOptionPane.showMessageDialog(
                panel,
                MSG_NOT_CONNECTED,
                TITLE_NOT_CONNECTED,
                JOptionPane.WARNING_MESSAGE
            );
            return false;
        }
        return true;
    }

    /**
     * Recursively search for and hide JTabbedPane components to remove nested tabs.
     */
    private void hideTabbedPane(Component component) {
        if (component instanceof JTabbedPane) {
            JTabbedPane tabbedPane = (JTabbedPane) component;
            // Hide the tab bar by setting it to not visible or removing the tab area
            // We can't make it invisible, but we can try to minimize its height
            tabbedPane.setUI(new javax.swing.plaf.basic.BasicTabbedPaneUI() {
                @Override
                protected int calculateTabAreaHeight(int tabPlacement, int horizRunCount, int maxTabHeight) {
                    return 0; // Hide tab area
                }
                
                @Override
                protected void paintTab(Graphics g, int tabPlacement, Rectangle[] rects, int tabIndex,
                                       Rectangle iconRect, Rectangle textRect) {
                    // Don't paint tabs
                }
            });
        } else if (component instanceof Container) {
            // Recursively search child components
            Container container = (Container) component;
            for (Component child : container.getComponents()) {
                hideTabbedPane(child);
            }
        }
    }

    @Override
    public HttpResponse getResponse() {
        // Return the response from Burp's editor
        return responseEditor.getResponse();
    }

    @Override
    public void setRequestResponse(HttpRequestResponse requestResponse) {
        this.currentRequestResponse = requestResponse;
        
        // Clear previous content when a new request is loaded
        renderedHtml = "";
        hasRendered = false;
        
        // Show/hide toolbar based on auto-render setting
        boolean autoRenderEnabled = settingsPanel.isAutoRenderEnabled();
        Component topPanel = panel.getComponent(0); // Get the toolbar panel
        topPanel.setVisible(!autoRenderEnabled);
        
        if (autoRenderEnabled) {
            // Auto-render if enabled and request exists
            if (requestResponse != null && requestResponse.request() != null) {
                // Trigger render after a short delay to ensure UI is ready
                SwingUtilities.invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        renderInBrowser();
                    }
                });
            }
        } else {
            showStatus("Ready - Click 'Render in Browser' to capture DOM content", Color.BLUE);
        }
    }

    @Override
    public Component uiComponent() {
        return panel;
    }

    @Override
    public String caption() {
        return TAB_CAPTION;
    }

    @Override
    public boolean isEnabledFor(HttpRequestResponse requestResponse) {
        // Enable for all HTTP responses
        return requestResponse != null && requestResponse.response() != null;
    }

    @Override
    public Selection selectedData() {
        // Delegate to the Burp editor
        return responseEditor.selection().orElse(null);
    }

    @Override
    public boolean isModified() {
        return responseEditor.isModified();
    }
}
