#!/bin/bash
# Download xterm.js and addons for the terminal

ASSETS_DIR="shared-assets/terminal"
XTERM_VERSION="5.5.0"
BASE_URL="https://cdn.jsdelivr.net/npm"

echo "Downloading xterm.js v${XTERM_VERSION}..."

curl -sL "${BASE_URL}/@xterm/xterm@${XTERM_VERSION}/css/xterm.min.css" -o "${ASSETS_DIR}/xterm.css"
curl -sL "${BASE_URL}/@xterm/xterm@${XTERM_VERSION}/lib/xterm.min.js" -o "${ASSETS_DIR}/xterm.js"
curl -sL "${BASE_URL}/@xterm/addon-fit@0.10.0/lib/addon-fit.min.js" -o "${ASSETS_DIR}/xterm-addon-fit.js"
curl -sL "${BASE_URL}/@xterm/addon-search@0.15.0/lib/addon-search.min.js" -o "${ASSETS_DIR}/xterm-addon-search.js"
curl -sL "${BASE_URL}/@xterm/addon-web-links@0.11.0/lib/addon-web-links.min.js" -o "${ASSETS_DIR}/xterm-addon-web-links.js"

echo "Done. Files in ${ASSETS_DIR}:"
ls -la "${ASSETS_DIR}/"

# Copy to platform asset directories
echo "Copying to Android assets..."
cp "${ASSETS_DIR}"/* androidApp/src/main/assets/terminal/

echo "Copying to Desktop resources..."
cp "${ASSETS_DIR}"/* desktopApp/src/main/resources/terminal/

echo "Assets setup complete."
