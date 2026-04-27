package com.burp.extension.redom.ui;

import com.burp.extension.redom.cdp.ChromeDevToolsClient;
import com.burp.extension.redom.cdp.PageRenderer;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.text.NumberFormatter;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class SettingsPanel extends JPanel implements burp.api.montoya.ui.settings.SettingsPanel {

    private static final String PREF_AUTO_RENDER        = "redom.autoRender";
    private static final String PREF_PRETTY_PRINT       = "redom.prettyPrint";
    private static final String PREF_CHROME_HOST        = "redom.chromeHost";
    private static final String PREF_CHROME_PORT        = "redom.chromePort";
    private static final String PREF_CDP_TIMEOUT        = "redom.cdpTimeout";
    private static final String PREF_PAGE_LOAD_TIMEOUT  = "redom.pageLoadTimeout";
    private static final String PREF_RENDER_DELAY       = "redom.renderDelay";
    private static final String PREF_CHROMIUM_PATH      = "redom.chromiumPath";
    private static final String PREF_PROXY_HOST         = "redom.proxyHost";
    private static final String PREF_PROXY_PORT         = "redom.proxyPort";
    private static final String PREF_USER_DATA_DIR      = "redom.userDataDir";
    private static final String PREF_IGNORE_CERT_ERRORS = "redom.ignoreCertErrors";
    private static final String PREF_AUTO_START         = "redom.autoStart";

    private final ChromeDevToolsClient cdpClient;
    private final PageRenderer pageRenderer;
    private final burp.api.montoya.MontoyaApi api;

    private final JLabel statusLabel;
    private final JButton launchConnectButton;
    private final JButton disconnectButton;
    private final JCheckBox autoRenderCheckBox;
    private final JCheckBox prettyPrintCheckBox;
    private final JTextField chromeHostField;
    private final JFormattedTextField chromePortField;
    private final JFormattedTextField cdpTimeoutField;
    private final JFormattedTextField pageLoadTimeoutField;
    private final JFormattedTextField renderDelayField;
    private final JTextField chromiumPathField;
    private final JTextField proxyHostField;
    private final JFormattedTextField proxyPortField;
    private final JTextField userDataDirField;
    private final JCheckBox ignoreCertErrorsCheckBox;
    private final JCheckBox autoStartCheckBox;

    private boolean autoRender = true;
    private Process chromiumProcess;

    public SettingsPanel(ChromeDevToolsClient cdpClient, burp.api.montoya.MontoyaApi api) {
        this.cdpClient = cdpClient;
        this.api = api;
        this.pageRenderer = new PageRenderer(cdpClient, api);

        setLayout(new BorderLayout());
        setBorder(new EmptyBorder(16, 16, 16, 16));

        // ── Status bar ──────────────────────────────────────────────────────────
        statusLabel = new JLabel("Not connected");
        statusLabel.setForeground(Color.RED);

        launchConnectButton = new JButton("Launch & Connect");
        launchConnectButton.addActionListener(e -> launchAndConnect());

        disconnectButton = new JButton("Disconnect");
        disconnectButton.setEnabled(false);
        disconnectButton.addActionListener(e -> disconnectFromChrome());

        JPanel statusBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        statusBar.add(new JLabel("Status:"));
        statusBar.add(statusLabel);
        statusBar.add(Box.createHorizontalStrut(16));
        statusBar.add(launchConnectButton);
        statusBar.add(disconnectButton);
        add(statusBar, BorderLayout.NORTH);

        // ── Settings body ────────────────────────────────────────────────────────
        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBorder(new EmptyBorder(12, 0, 0, 0));

        // Rendering
        autoRenderCheckBox = new JCheckBox("Render on tab selection");
        autoRenderCheckBox.setSelected(true);
        autoRenderCheckBox.addActionListener(e -> {
            autoRender = autoRenderCheckBox.isSelected();
            savePreference(PREF_AUTO_RENDER, autoRender);
        });

        prettyPrintCheckBox = new JCheckBox("Pretty-print HTML");
        prettyPrintCheckBox.setSelected(true);
        prettyPrintCheckBox.addActionListener(e -> {
            boolean v = prettyPrintCheckBox.isSelected();
            pageRenderer.setShowPrettyTab(v);
            savePreference(PREF_PRETTY_PRINT, v);
        });

        renderDelayField = intField(1000, 0, 10000);
        onSave(renderDelayField, () -> {
            int v = ((Number) renderDelayField.getValue()).intValue();
            pageRenderer.setRenderDelay(v);
            savePreference(PREF_RENDER_DELAY, v);
        });

        pageLoadTimeoutField = intField(30, 5, 120);
        onSave(pageLoadTimeoutField, () -> {
            int v = ((Number) pageLoadTimeoutField.getValue()).intValue();
            pageRenderer.setPageLoadTimeout(v);
            savePreference(PREF_PAGE_LOAD_TIMEOUT, v);
        });

        GridForm rendering = new GridForm();
        rendering.addField("Post-load delay", renderDelayField, "ms");
        rendering.addField("Load timeout", pageLoadTimeoutField, "seconds");
        rendering.addCheckBox(autoRenderCheckBox);
        rendering.addCheckBox(prettyPrintCheckBox);

        // Chrome
        chromeHostField = new JTextField("localhost", 16);
        onSave(chromeHostField, () -> {
            String v = chromeHostField.getText().trim();
            cdpClient.setChromeHost(v);
            savePreference(PREF_CHROME_HOST, v);
        });

        chromePortField = intField(9222, 1024, 65535);
        onSave(chromePortField, () -> {
            int v = ((Number) chromePortField.getValue()).intValue();
            cdpClient.setChromePort(v);
            savePreference(PREF_CHROME_PORT, v);
        });

        cdpTimeoutField = intField(30, 5, 120);
        onSave(cdpTimeoutField, () -> {
            int v = ((Number) cdpTimeoutField.getValue()).intValue();
            cdpClient.setTimeout(v);
            savePreference(PREF_CDP_TIMEOUT, v);
        });

        GridForm chrome = new GridForm();
        chrome.addField("Host", chromeHostField, null);
        chrome.addField("Port", chromePortField, null);
        chrome.addField("Command timeout", cdpTimeoutField, "seconds");

        // Browser
        chromiumPathField = new JTextField("chromium", 20);
        onSave(chromiumPathField, () -> savePreference(PREF_CHROMIUM_PATH, chromiumPathField.getText().trim()));

        proxyHostField = new JTextField("localhost", 14);
        onSave(proxyHostField, () -> savePreference(PREF_PROXY_HOST, proxyHostField.getText().trim()));

        proxyPortField = intField(8080, 1, 65535);
        onSave(proxyPortField, () -> savePreference(PREF_PROXY_PORT, ((Number) proxyPortField.getValue()).intValue()));

        JPanel proxyFields = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        proxyFields.add(proxyHostField);
        proxyFields.add(new JLabel(":"));
        proxyFields.add(proxyPortField);

        userDataDirField = new JTextField("/tmp/redom", 20);
        onSave(userDataDirField, () -> savePreference(PREF_USER_DATA_DIR, userDataDirField.getText().trim()));

        ignoreCertErrorsCheckBox = new JCheckBox("Accept invalid certificates");
        ignoreCertErrorsCheckBox.setSelected(true);
        ignoreCertErrorsCheckBox.addActionListener(e -> savePreference(PREF_IGNORE_CERT_ERRORS, ignoreCertErrorsCheckBox.isSelected()));

        autoStartCheckBox = new JCheckBox("Launch browser on startup");
        autoStartCheckBox.setSelected(false);
        autoStartCheckBox.addActionListener(e -> savePreference(PREF_AUTO_START, autoStartCheckBox.isSelected()));

        GridForm launch = new GridForm();
        launch.addField("Browser path", chromiumPathField, null);
        launch.addField("Upstream proxy", proxyFields, null);
        launch.addField("Profile directory", userDataDirField, null);
        launch.addCheckBox(ignoreCertErrorsCheckBox);
        launch.addCheckBox(autoStartCheckBox);

        body.add(section("Browser", launch.panel()));
        body.add(Box.createVerticalStrut(12));
        body.add(section("Connection", chrome.panel()));
        body.add(Box.createVerticalStrut(12));
        body.add(section("Rendering", rendering.panel()));

        JScrollPane scroll = new JScrollPane(body,
            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
            JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        add(scroll, BorderLayout.CENTER);

        loadPreferences();
        updateStatus();

        if (autoStartCheckBox.isSelected()) {
            SwingUtilities.invokeLater(this::launchAndConnect);
        }

        addComponentListener(new ComponentAdapter() {
            @Override public void componentShown(ComponentEvent e) { updateStatus(); }
        });
    }

    // ==================== SettingsPanel interface ====================

    @Override public JComponent uiComponent() { return this; }

    @Override
    public Set<String> keywords() {
        return Set.of("reDOM", "chrome", "chromium", "CDP", "render", "DOM", "proxy");
    }

    // ==================== Public accessors ====================

    public boolean isAutoRenderEnabled() { return autoRender; }
    public PageRenderer getPageRenderer() { return pageRenderer; }
    public ChromeDevToolsClient getCdpClient() { return cdpClient; }
    public Process getChromiumProcess() { return chromiumProcess; }

    // ==================== Actions ====================

    private void launchAndConnect() {
        launchConnectButton.setEnabled(false);
        statusLabel.setText("Launching...");
        statusLabel.setForeground(Color.ORANGE);

        SwingWorker<Void, Void> worker = new SwingWorker<>() {
            private String errorMessage;

            @Override
            protected Void doInBackground() throws Exception {
                List<String> cmd = new ArrayList<>();
                cmd.add(chromiumPathField.getText().trim());
                cmd.add("--proxy-server=" + proxyHostField.getText().trim() + ":" + ((Number) proxyPortField.getValue()).intValue());
                cmd.add("--remote-debugging-port=" + ((Number) chromePortField.getValue()).intValue());
                cmd.add("--user-data-dir=" + userDataDirField.getText().trim());
                if (ignoreCertErrorsCheckBox.isSelected()) cmd.add("--ignore-certificate-errors");
                cmd.add("--no-first-run");
                cmd.add("--no-default-browser-check");

                if (chromiumProcess != null && chromiumProcess.isAlive()) chromiumProcess.destroy();
                chromiumProcess = new ProcessBuilder(cmd).redirectErrorStream(true).start();

                cdpClient.waitForChrome(15);

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
                    JOptionPane.showMessageDialog(SettingsPanel.this,
                        "Connected to Chrome.", "Connected", JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception ex) {
                    updateStatus();
                    JOptionPane.showMessageDialog(SettingsPanel.this,
                        "Failed to launch or connect:\n\n" + (errorMessage != null ? errorMessage : ex.getMessage()),
                        "Launch Error", JOptionPane.ERROR_MESSAGE);
                    launchConnectButton.setEnabled(true);
                }
            }
        };
        worker.execute();
    }

    private void disconnectFromChrome() {
        cdpClient.disconnect();
        updateStatus();
    }

    private void updateStatus() {
        if (cdpClient.isConnected()) {
            statusLabel.setText(cdpClient.getConnectionStatus());
            statusLabel.setForeground(new Color(0, 128, 0));
            launchConnectButton.setEnabled(false);
            disconnectButton.setEnabled(true);
        } else {
            statusLabel.setText("Not connected");
            statusLabel.setForeground(Color.RED);
            launchConnectButton.setEnabled(true);
            disconnectButton.setEnabled(false);
        }
    }

    // ==================== Preferences ====================

    private void loadPreferences() {
        Boolean savedAutoRender = api.persistence().preferences().getBoolean(PREF_AUTO_RENDER);
        if (savedAutoRender != null) { autoRender = savedAutoRender; autoRenderCheckBox.setSelected(autoRender); }

        Boolean savedPrettyPrint = api.persistence().preferences().getBoolean(PREF_PRETTY_PRINT);
        if (savedPrettyPrint != null) { prettyPrintCheckBox.setSelected(savedPrettyPrint); pageRenderer.setShowPrettyTab(savedPrettyPrint); }

        String savedHost = api.persistence().preferences().getString(PREF_CHROME_HOST);
        if (savedHost != null && !savedHost.isEmpty()) { chromeHostField.setText(savedHost); cdpClient.setChromeHost(savedHost); }

        Integer savedPort = api.persistence().preferences().getInteger(PREF_CHROME_PORT);
        if (savedPort != null) { chromePortField.setValue(savedPort); cdpClient.setChromePort(savedPort); }

        Integer savedCdpTimeout = api.persistence().preferences().getInteger(PREF_CDP_TIMEOUT);
        if (savedCdpTimeout != null) { cdpTimeoutField.setValue(savedCdpTimeout); cdpClient.setTimeout(savedCdpTimeout); }

        Integer savedPageTimeout = api.persistence().preferences().getInteger(PREF_PAGE_LOAD_TIMEOUT);
        if (savedPageTimeout != null) { pageLoadTimeoutField.setValue(savedPageTimeout); pageRenderer.setPageLoadTimeout(savedPageTimeout); }

        Integer savedRenderDelay = api.persistence().preferences().getInteger(PREF_RENDER_DELAY);
        if (savedRenderDelay != null) { renderDelayField.setValue(savedRenderDelay); pageRenderer.setRenderDelay(savedRenderDelay); }

        String savedChromiumPath = api.persistence().preferences().getString(PREF_CHROMIUM_PATH);
        if (savedChromiumPath != null && !savedChromiumPath.isEmpty()) { chromiumPathField.setText(savedChromiumPath); }

        String savedProxyHost = api.persistence().preferences().getString(PREF_PROXY_HOST);
        if (savedProxyHost != null && !savedProxyHost.isEmpty()) { proxyHostField.setText(savedProxyHost); }

        Integer savedProxyPort = api.persistence().preferences().getInteger(PREF_PROXY_PORT);
        if (savedProxyPort != null) { proxyPortField.setValue(savedProxyPort); }

        String savedUserDataDir = api.persistence().preferences().getString(PREF_USER_DATA_DIR);
        if (savedUserDataDir != null && !savedUserDataDir.isEmpty()) { userDataDirField.setText(savedUserDataDir); }

        Boolean savedIgnoreCert = api.persistence().preferences().getBoolean(PREF_IGNORE_CERT_ERRORS);
        if (savedIgnoreCert != null) { ignoreCertErrorsCheckBox.setSelected(savedIgnoreCert); }

        Boolean savedAutoStart = api.persistence().preferences().getBoolean(PREF_AUTO_START);
        if (savedAutoStart != null) { autoStartCheckBox.setSelected(savedAutoStart); }
    }

    private void savePreference(String key, boolean value) { api.persistence().preferences().setBoolean(key, value); }
    private void savePreference(String key, String value)  { api.persistence().preferences().setString(key, value); }
    private void savePreference(String key, int value)     { api.persistence().preferences().setInteger(key, value); }

    // ==================== UI helpers ====================

    private static JPanel section(String title, JPanel content) {
        JLabel header = new JLabel(title);
        header.setFont(header.getFont().deriveFont(Font.BOLD));

        JSeparator sep = new JSeparator();
        sep.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));

        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setAlignmentX(LEFT_ALIGNMENT);

        JPanel headerRow = new JPanel(new BorderLayout(8, 0));
        headerRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));
        headerRow.add(header, BorderLayout.WEST);
        headerRow.add(sep, BorderLayout.CENTER);

        content.setBorder(new EmptyBorder(6, 0, 0, 0));
        panel.add(headerRow);
        panel.add(content);
        return panel;
    }

    private static JFormattedTextField intField(int value, int min, int max) {
        NumberFormat fmt = NumberFormat.getIntegerInstance();
        fmt.setGroupingUsed(false);
        NumberFormatter formatter = new NumberFormatter(fmt);
        formatter.setMinimum(min);
        formatter.setMaximum(max);
        formatter.setAllowsInvalid(false);
        formatter.setCommitsOnValidEdit(true);
        JFormattedTextField field = new JFormattedTextField(formatter);
        field.setValue(value);
        field.setColumns(6);
        return field;
    }

    private static void onSave(JTextField field, Runnable save) {
        field.addActionListener(e -> save.run());
        field.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) { save.run(); }
        });
    }

    private static class GridForm {
        private final JPanel panel = new JPanel(new GridBagLayout());
        private int row = 0;

        void addField(String label, JComponent field, String unit) {
            GridBagConstraints lc = new GridBagConstraints();
            lc.gridx = 0; lc.gridy = row;
            lc.anchor = GridBagConstraints.WEST;
            lc.insets = new Insets(3, 0, 3, 8);
            panel.add(new JLabel(label), lc);

            GridBagConstraints fc = new GridBagConstraints();
            fc.gridx = 1; fc.gridy = row;
            fc.anchor = GridBagConstraints.WEST;
            fc.insets = new Insets(3, 0, 3, 0);
            panel.add(field, fc);

            if (unit != null) {
                JLabel unitLabel = new JLabel(unit);
                unitLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
                GridBagConstraints uc = new GridBagConstraints();
                uc.gridx = 2; uc.gridy = row;
                uc.anchor = GridBagConstraints.WEST;
                uc.insets = new Insets(3, 6, 3, 0);
                panel.add(unitLabel, uc);
            }
            row++;
        }

        void addCheckBox(JCheckBox box) {
            GridBagConstraints c = new GridBagConstraints();
            c.gridx = 1; c.gridy = row;
            c.anchor = GridBagConstraints.WEST;
            c.insets = new Insets(3, 0, 3, 0);
            panel.add(box, c);
            row++;
        }

        JPanel panel() {
            // Push grid to top-left by filling remaining space
            GridBagConstraints hFill = new GridBagConstraints();
            hFill.gridx = 3; hFill.gridy = 0; hFill.weightx = 1;
            hFill.fill = GridBagConstraints.HORIZONTAL;
            panel.add(new JLabel(), hFill);

            GridBagConstraints vFill = new GridBagConstraints();
            vFill.gridx = 0; vFill.gridy = row; vFill.weighty = 1;
            vFill.fill = GridBagConstraints.VERTICAL;
            panel.add(new JLabel(), vFill);

            return panel;
        }
    }
}
