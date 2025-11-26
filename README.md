# reDOM

A Burp Suite extension that renders JavaScript-heavy pages using Chrome DevTools Protocol to capture the final DOM state.

## Features

- Captures fully-rendered DOM after JavaScript execution
- Integrates as a custom response tab in Burp Repeater
- Auto-render option for automatic DOM capture
- Configurable Chrome connection and rendering parameters

## Requirements

- Burp Suite Professional/Community
- Chrome/Chromium browser

## Installation

1. Build the extension:
   ```bash
   mvn clean package
   ```

2. Load `target/reDOM.jar` in Burp Suite (Extensions → Add)

## Usage

1. Start a Chromium based browser with remote debugging:
   ```bash
   chromium -proxy-server=localhost:8080 --remote-debugging-port=9222 --user-data-dir=/tmp/redom --ignore-certificate-errors
   ```

2. In Burp, go to reDOM settings tab and click "Connect to Chrome"

3. The extension will spawn a minimized browser window for rendering

4. Send a request to Repeater and switch to the "DOM Render" tab

5. Click "Render in Browser" or enable "Auto render" for automatic rendering

## Configuration

Available settings:
- **Chrome Host/Port**: Connection details (default: localhost:9222)
- **CDP Command Timeout**: WebSocket command timeout in seconds (default: 30)
- **Page Load Timeout**: Maximum time to wait for page load (default: 30)
- **Render Delay**: Additional wait time after page load in ms (default: 1000)
- **Auto Render**: Automatically render when tab opens
- **Minimize Window**: Minimize Chrome window during rendering

## License

MIT License
