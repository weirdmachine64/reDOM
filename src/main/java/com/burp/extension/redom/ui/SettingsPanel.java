package com.burp.extension.redom.ui;

import com.burp.extension.redom.cdp.ChromeDevToolsClient;
import com.burp.extension.redom.cdp.PageRenderer;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;

/**
 * Settings panel for configuring Chrome DevTools Protocol connection and reDOM behavior.
 * 
 * Features:
 * - Chrome connection management (connect/disconnect)
 * - Auto-render toggle for automatic DOM rendering
 * - Pretty print preference for response display
 * - Window minimization control for hidden Chrome operation
 * - On-demand connection status monitoring
 * 
 * Performance: Status is checked on-demand rather than polling, reducing overhead
 */
public class SettingsPanel extends JPanel {
    
    // Preference keys for persistent storage
    private static final String PREF_AUTO_RENDER = "redom.autoRender";
    private static final String PREF_PRETTY_PRINT = "redom.prettyPrint";
    private static final String PREF_MINIMIZE_WINDOW = "redom.minimizeWindow";
    private static final String PREF_CHROME_HOST = "redom.chromeHost";
    private static final String PREF_CHROME_PORT = "redom.chromePort";
    private static final String PREF_CDP_TIMEOUT = "redom.cdpTimeout";
    private static final String PREF_PAGE_LOAD_TIMEOUT = "redom.pageLoadTimeout";
    private static final String PREF_RENDER_DELAY = "redom.renderDelay";
    
    // UI Constants
    private static final String TITLE_REDOM = "reDOM - Settings";
    private static final String BTN_CONNECT = "Connect to Chrome";
    private static final String BTN_DISCONNECT = "Disconnect";
    private static final String STATUS_NOT_CONNECTED = "Not connected";
    private static final String STATUS_CONNECTING = "Connecting...";
    private static final String MSG_CONNECT_SUCCESS = "Successfully connected to Chrome!";
    private static final String MSG_CONNECT_ERROR = "Failed to connect to Chrome";
    private static final String MSG_DISCONNECTED = "Disconnected from Chrome";
    private static final String CHROME_LAUNCH_CMD = "--remote-debugging-port=9222";
    
    private final ChromeDevToolsClient cdpClient;
    private final PageRenderer pageRenderer;
    private final burp.api.montoya.MontoyaApi api;
    private final JLabel statusLabel;
    private final JButton connectButton;
    private final JButton disconnectButton;
    private final JTextArea infoArea;
    private final JCheckBox autoRenderCheckBox;
    private final JCheckBox prettyPrintCheckBox;
    private final JCheckBox minimizeWindowCheckBox;
    private final JTextField chromeHostField;
    private final JSpinner chromePortSpinner;
    private final JSpinner cdpTimeoutSpinner;
    private final JSpinner pageLoadTimeoutSpinner;
    private final JSpinner renderDelaySpinner;
    
    private boolean autoRender = true;

    public SettingsPanel(ChromeDevToolsClient cdpClient, burp.api.montoya.MontoyaApi api) {
        this.cdpClient = cdpClient;
        this.api = api;
        this.pageRenderer = new PageRenderer(cdpClient, api);
        
        setLayout(new BorderLayout(10, 10));
        setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));
        
        // Title panel
        JPanel titlePanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JLabel titleLabel = new JLabel(TITLE_REDOM);
        titleLabel.setFont(new Font("Sans-Serif", Font.BOLD, 16));
        titlePanel.add(titleLabel);
        
        // Status panel
        JPanel statusPanel = new JPanel(new BorderLayout(10, 10));
        statusPanel.setBorder(BorderFactory.createTitledBorder("Settings"));
        
        statusLabel = new JLabel(STATUS_NOT_CONNECTED);
        statusLabel.setFont(new Font("Sans-Serif", Font.PLAIN, 14));
        statusLabel.setForeground(Color.RED);
        
        JPanel statusInnerPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        statusInnerPanel.add(new JLabel("Status: "));
        statusInnerPanel.add(statusLabel);
        
        statusPanel.add(statusInnerPanel, BorderLayout.NORTH);
        
        // Buttons panel
        JPanel buttonsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        connectButton = new JButton(BTN_CONNECT);
        disconnectButton = new JButton(BTN_DISCONNECT);
        disconnectButton.setEnabled(false);
        
        connectButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                connectToChrome();
            }
        });
        
        disconnectButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                disconnectFromChrome();
            }
        });
        
        buttonsPanel.add(connectButton);
        buttonsPanel.add(disconnectButton);
        statusPanel.add(buttonsPanel, BorderLayout.CENTER);
        
        // Options panel
        JPanel optionsPanel = new JPanel();
        optionsPanel.setLayout(new BoxLayout(optionsPanel, BoxLayout.Y_AXIS));
        
        // Auto-render option
        JPanel autoRenderPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        autoRenderCheckBox = new JCheckBox("Auto render when DOM Render tab is selected (default: on)");
        autoRenderCheckBox.setSelected(true);
        autoRenderCheckBox.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                autoRender = autoRenderCheckBox.isSelected();
                savePreference(PREF_AUTO_RENDER, autoRender);
            }
        });
        autoRenderPanel.add(autoRenderCheckBox);
        optionsPanel.add(autoRenderPanel);
        
        // Pretty tab option
        JPanel prettyPrintPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        prettyPrintCheckBox = new JCheckBox("Prettify Response (default: on)");
        prettyPrintCheckBox.setSelected(true); // Default to true
        prettyPrintCheckBox.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                boolean selected = prettyPrintCheckBox.isSelected();
                pageRenderer.setShowPrettyTab(selected);
                savePreference(PREF_PRETTY_PRINT, selected);
            }
        });
        prettyPrintPanel.add(prettyPrintCheckBox);
        optionsPanel.add(prettyPrintPanel);
        
        // Minimize window option
        JPanel minimizeWindowPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        minimizeWindowCheckBox = new JCheckBox("Start browser window minimized (default: on)");
        minimizeWindowCheckBox.setSelected(true); // Default to true
        minimizeWindowCheckBox.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                boolean selected = minimizeWindowCheckBox.isSelected();
                cdpClient.setMinimizeWindow(selected);
                savePreference(PREF_MINIMIZE_WINDOW, selected);
            }
        });
        minimizeWindowPanel.add(minimizeWindowCheckBox);
        optionsPanel.add(minimizeWindowPanel);
        
        // Chrome host setting
        JPanel hostPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        hostPanel.add(new JLabel("Chrome Host:"));
        chromeHostField = new JTextField("localhost", 15);
        chromeHostField.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                String host = chromeHostField.getText().trim();
                cdpClient.setChromeHost(host);
                savePreference(PREF_CHROME_HOST, host);
            }
        });
        hostPanel.add(chromeHostField);
        hostPanel.add(new JLabel("(localhost or IP address)"));
        optionsPanel.add(hostPanel);
        
        // Chrome port setting
        JPanel portPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        portPanel.add(new JLabel("Chrome Port:"));
        chromePortSpinner = new JSpinner(new SpinnerNumberModel(9222, 1024, 65535, 1));
        chromePortSpinner.addChangeListener(evt -> {
            int port = (Integer) chromePortSpinner.getValue();
            cdpClient.setChromePort(port);
            savePreference(PREF_CHROME_PORT, port);
        });
        portPanel.add(chromePortSpinner);
        portPanel.add(new JLabel("(default: 9222)"));
        optionsPanel.add(portPanel);
        
        // CDP timeout setting
        JPanel cdpTimeoutPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        cdpTimeoutPanel.add(new JLabel("CDP Command Timeout:"));
        cdpTimeoutSpinner = new JSpinner(new SpinnerNumberModel(30, 5, 120, 5));
        cdpTimeoutSpinner.addChangeListener(evt -> {
            int timeout = (Integer) cdpTimeoutSpinner.getValue();
            cdpClient.setTimeout(timeout);
            savePreference(PREF_CDP_TIMEOUT, timeout);
        });
        cdpTimeoutPanel.add(cdpTimeoutSpinner);
        cdpTimeoutPanel.add(new JLabel("seconds (default: 30)"));
        optionsPanel.add(cdpTimeoutPanel);
        
        // Page load timeout setting
        JPanel pageTimeoutPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        pageTimeoutPanel.add(new JLabel("Page Load Timeout:"));
        pageLoadTimeoutSpinner = new JSpinner(new SpinnerNumberModel(30, 5, 120, 5));
        pageLoadTimeoutSpinner.addChangeListener(evt -> {
            int timeout = (Integer) pageLoadTimeoutSpinner.getValue();
            pageRenderer.setPageLoadTimeout(timeout);
            savePreference(PREF_PAGE_LOAD_TIMEOUT, timeout);
        });
        pageTimeoutPanel.add(pageLoadTimeoutSpinner);
        pageTimeoutPanel.add(new JLabel("seconds (default: 30)"));
        optionsPanel.add(pageTimeoutPanel);
        
        // Render delay setting
        JPanel renderDelayPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        renderDelayPanel.add(new JLabel("Render Delay:"));
        renderDelaySpinner = new JSpinner(new SpinnerNumberModel(1000, 0, 10000, 100));
        renderDelaySpinner.addChangeListener(evt -> {
            int delay = (Integer) renderDelaySpinner.getValue();
            pageRenderer.setRenderDelay(delay);
            savePreference(PREF_RENDER_DELAY, delay);
        });
        renderDelayPanel.add(renderDelaySpinner);
        renderDelayPanel.add(new JLabel("ms (default: 1000)"));
        optionsPanel.add(renderDelayPanel);
        
        statusPanel.add(optionsPanel, BorderLayout.SOUTH);
        
        // Info panel
        JPanel infoPanel = new JPanel(new BorderLayout());
        infoPanel.setBorder(BorderFactory.createTitledBorder("Instructions"));
        
        infoArea = new JTextArea();
        infoArea.setEditable(false);
        infoArea.setFont(new Font("Arial", Font.PLAIN, 20));
        infoArea.setLineWrap(true);
        infoArea.setWrapStyleWord(true);
        infoArea.setText(
            "1. Start a Chromium based browser with remote debugging enabled (" + CHROME_LAUNCH_CMD + ")\n\n" +
            "2. Click 'Connect to Chrome' above\n\n" +
            "3. Open a request in Burp Repeater\n\n" +
            "4. Click the 'DOM Render' tab in the response area\n\n" +
            "5. Either:\n" +
            "   - If 'Auto render' is enabled, response will be automatically rendered\n\n" +
            "   - Else, Click 'Render in Browser' button to manually render\n" 
        );
        
        JScrollPane infoScroll = new JScrollPane(infoArea);
        infoScroll.setPreferredSize(new Dimension(600, 300));
        infoPanel.add(infoScroll, BorderLayout.CENTER);
        
        // Add all panels
        add(titlePanel, BorderLayout.NORTH);
        
        JPanel centerPanel = new JPanel(new BorderLayout(0, 0));
        centerPanel.add(statusPanel, BorderLayout.NORTH);
        centerPanel.add(infoPanel, BorderLayout.CENTER);
        
        add(centerPanel, BorderLayout.CENTER);
        
        // Load saved preferences and apply them to UI
        loadPreferences();
        
        // Initial status update
        updateStatus();
        
        // Add listener to update status when panel becomes visible
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentShown(ComponentEvent e) {
                updateStatus();
            }
        });
    }

    /**
     * Connect to Chrome browser using SwingWorker for background execution.
     */
    private void connectToChrome() {
        connectButton.setEnabled(false);
        statusLabel.setText(STATUS_CONNECTING);
        statusLabel.setForeground(Color.ORANGE);
        
        SwingWorker<Void, Void> worker = new SwingWorker<Void, Void>() {
            private String errorMessage = null;
            
            @Override
            protected Void doInBackground() throws Exception {
                try {
                    cdpClient.connect();
                } catch (Exception e) {
                    errorMessage = e.getMessage();
                    throw e;
                }
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    updateStatus();
                    JOptionPane.showMessageDialog(
                        SettingsPanel.this,
                        MSG_CONNECT_SUCCESS,
                        "Connection Success",
                        JOptionPane.INFORMATION_MESSAGE
                    );
                } catch (Exception ex) {
                    updateStatus();
                    String errorDetails = errorMessage != null ? errorMessage : ex.getMessage();
                    JOptionPane.showMessageDialog(
                        SettingsPanel.this,
                        MSG_CONNECT_ERROR + ":\n\n" + errorDetails +
                        "\n\nMake sure Chrome is running with:\n" + CHROME_LAUNCH_CMD,
                        "Connection Error",
                        JOptionPane.ERROR_MESSAGE
                    );
                    connectButton.setEnabled(true);
                }
            }
        };
        
        worker.execute();
    }

    /**
     * Disconnect from Chrome browser.
     */
    private void disconnectFromChrome() {
        cdpClient.disconnect();
        updateStatus();
        JOptionPane.showMessageDialog(
            this,
            MSG_DISCONNECTED,
            "Disconnected",
            JOptionPane.INFORMATION_MESSAGE
        );
    }

    /**
     * Update status display based on current connection state.
     * Called on-demand rather than via polling for better performance.
     */
    private void updateStatus() {
        if (cdpClient.isConnected()) {
            statusLabel.setText(cdpClient.getConnectionStatus());
            statusLabel.setForeground(new Color(0, 128, 0));
            connectButton.setEnabled(false);
            disconnectButton.setEnabled(true);
        } else {
            statusLabel.setText(STATUS_NOT_CONNECTED);
            statusLabel.setForeground(Color.RED);
            connectButton.setEnabled(true);
            disconnectButton.setEnabled(false);
        }
    }
    
    /**
     * Check if auto-render is enabled.
     */
    public boolean isAutoRenderEnabled() {
        return autoRender;
    }
    
    /**
     * Get the PageRenderer instance.
     */
    public PageRenderer getPageRenderer() {
        return pageRenderer;
    }
    
    /**
     * Get the ChromeDevToolsClient instance.
     */
    public ChromeDevToolsClient getCdpClient() {
        return cdpClient;
    }
    
    /**
     * Load preferences from Burp Suite's persistent storage.
     */
    private void loadPreferences() {
        // Load auto-render preference
        Boolean savedAutoRender = api.persistence().preferences().getBoolean(PREF_AUTO_RENDER);
        if (savedAutoRender != null) {
            autoRender = savedAutoRender;
            autoRenderCheckBox.setSelected(autoRender);
        }
        
        // Load pretty-print preference (default true)
        Boolean savedPrettyPrint = api.persistence().preferences().getBoolean(PREF_PRETTY_PRINT);
        if (savedPrettyPrint != null) {
            prettyPrintCheckBox.setSelected(savedPrettyPrint);
            pageRenderer.setShowPrettyTab(savedPrettyPrint);
        }
        
        // Load minimize window preference (default true)
        Boolean savedMinimizeWindow = api.persistence().preferences().getBoolean(PREF_MINIMIZE_WINDOW);
        if (savedMinimizeWindow != null) {
            minimizeWindowCheckBox.setSelected(savedMinimizeWindow);
            cdpClient.setMinimizeWindow(savedMinimizeWindow);
        }
        
        // Load Chrome host preference
        String savedHost = api.persistence().preferences().getString(PREF_CHROME_HOST);
        if (savedHost != null && !savedHost.isEmpty()) {
            chromeHostField.setText(savedHost);
            cdpClient.setChromeHost(savedHost);
        }
        
        // Load Chrome port preference
        Integer savedPort = api.persistence().preferences().getInteger(PREF_CHROME_PORT);
        if (savedPort != null) {
            chromePortSpinner.setValue(savedPort);
            cdpClient.setChromePort(savedPort);
        }
        
        // Load CDP timeout preference
        Integer savedCdpTimeout = api.persistence().preferences().getInteger(PREF_CDP_TIMEOUT);
        if (savedCdpTimeout != null) {
            cdpTimeoutSpinner.setValue(savedCdpTimeout);
            cdpClient.setTimeout(savedCdpTimeout);
        }
        
        // Load page load timeout preference
        Integer savedPageTimeout = api.persistence().preferences().getInteger(PREF_PAGE_LOAD_TIMEOUT);
        if (savedPageTimeout != null) {
            pageLoadTimeoutSpinner.setValue(savedPageTimeout);
            pageRenderer.setPageLoadTimeout(savedPageTimeout);
        }
        
        // Load render delay preference
        Integer savedRenderDelay = api.persistence().preferences().getInteger(PREF_RENDER_DELAY);
        if (savedRenderDelay != null) {
            renderDelaySpinner.setValue(savedRenderDelay);
            pageRenderer.setRenderDelay(savedRenderDelay);
        }
    }
    
    /**
     * Save a boolean preference.
     */
    private void savePreference(String key, boolean value) {
        api.persistence().preferences().setBoolean(key, value);
    }
    
    /**
     * Save a string preference.
     */
    private void savePreference(String key, String value) {
        api.persistence().preferences().setString(key, value);
    }
    
    /**
     * Save an integer preference.
     */
    private void savePreference(String key, int value) {
        api.persistence().preferences().setInteger(key, value);
    }
}
