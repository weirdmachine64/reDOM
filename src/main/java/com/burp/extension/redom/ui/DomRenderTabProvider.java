package com.burp.extension.redom.ui;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.ui.editor.extension.EditorCreationContext;
import burp.api.montoya.ui.editor.extension.ExtensionProvidedHttpResponseEditor;
import burp.api.montoya.ui.editor.extension.HttpResponseEditorProvider;

/**
 * Provider that creates DomRenderTab instances for HTTP response editors.
 * 
 * This provider is registered with Burp Suite to add a custom "DOM Render" tab
 * to the HTTP response editor UI. Each request/response view gets its own
 * DomRenderTab instance.
 * 
 * Lifecycle: Burp calls provideHttpResponseEditor() when creating response editors
 * (e.g., in Repeater, Proxy History, etc.)
 */
public class DomRenderTabProvider implements HttpResponseEditorProvider {
    
    private final MontoyaApi api;
    private final SettingsPanel settingsPanel;

    /**
     * Create a new DomRenderTabProvider.
     * 
     * @param api Burp's Montoya API instance
     * @param settingsPanel shared settings panel for configuration
     */
    public DomRenderTabProvider(MontoyaApi api, SettingsPanel settingsPanel) {
        this.api = api;
        this.settingsPanel = settingsPanel;
    }

    /**
     * Provide a new DomRenderTab instance for an HTTP response editor.
     * 
     * @param creationContext context information about where the editor is being created
     * @return new DomRenderTab instance
     */
    @Override
    public ExtensionProvidedHttpResponseEditor provideHttpResponseEditor(EditorCreationContext creationContext) {
        return new DomRenderTab(creationContext, api, settingsPanel);
    }
}
