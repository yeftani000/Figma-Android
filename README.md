# Figma web wrapper (Android)

Personal-use multi-tab WebView wrapper around figma.com. Not affiliated with Figma.

## Build
1. Push this folder to your GitHub repo (yeftani000/Figma-Android).
2. Every push builds a signed APK (Actions tab -> artifact `figma-wrapper-apk`).

## Releasing an update
1. On GitHub: Releases -> Draft a new release -> create a new tag like `v1.1` (must start with `v`) -> Publish.
2. The workflow builds the APK and attaches `FigmaWrapper-1.1.apk` to that release (takes a few minutes).
3. In the app: gear icon -> Settings -> **Check for updates** (it also checks automatically every 12 h).
   It downloads the APK and opens the Android installer.
Tags must increase (v1.1, v1.2, ...). The first time, Android asks to allow installs from this app.

## Signing (GitHub secrets, one-time setup)
Updates only install over each other if every build is signed with the same key. The key lives in GitHub secrets, not in the repo.

1. Make a keystore on your computer (needs Java's `keytool`) and keep a backup of it somewhere safe - if you lose it you can't ship updates:
   `keytool -genkeypair -v -keystore release.keystore -alias figmawrapper -keyalg RSA -keysize 2048 -validity 36500`
2. Turn it into base64:
   - Linux: `base64 -w0 release.keystore`
   - macOS: `base64 -i release.keystore | tr -d '\n'`
   - Windows PowerShell: `[Convert]::ToBase64String([IO.File]::ReadAllBytes("release.keystore"))`
3. Repo -> Settings -> Secrets and variables -> Actions -> New repository secret. Add four:
   - `KEYSTORE_BASE64` = the base64 text
   - `KEYSTORE_PASSWORD` = the store password you chose
   - `KEY_ALIAS` = `figmawrapper` (or your alias)
   - `KEY_PASSWORD` = the key password you chose
Tag (release) builds fail on purpose if the secrets are missing. Normal pushes still build, with a throwaway key.

## Tabs
- Pinned **Home** tab (figma.com/files). Clicking a file there opens it in a new tab.
- **+** button or Ctrl+T opens a new tab (file browser). Tabs scroll horizontally.
- Tabs are restored when you reopen the app (loaded lazily when selected).
- Back button: navigates history; on a file tab with no history it closes the tab.

## Keyboard shortcuts (hardware keyboard; Ctrl or Meta/Cmd)
| Shortcut | Action |
|---|---|
| Ctrl+T / Ctrl+N | New tab |
| Ctrl+W | Close current tab (Home can't be closed) |
| Ctrl+Shift+T | Reopen last closed tab |
| Ctrl+Tab / Ctrl+Shift+Tab | Next / previous tab |
| Ctrl+PageDown / Ctrl+PageUp | Next / previous tab |
| Ctrl+1 ... Ctrl+8 | Jump to tab N |
| Ctrl+9 | Jump to last tab |
| F5 | Reload current tab (also the reload button in the tab bar) |

All other keys (V, R, F, Ctrl+C/Z/D, etc.) pass through to the Figma editor,
which handles its own shortcuts. To change the tab shortcuts, edit `shortcutFor()` in MainActivity.kt.

## Pen / stylus (untested - built for active pens like the Xiaomi Focus Pen Pro)
Tap the gear icon at the top right -> Settings:
- **Palm rejection:** finger touches that start while the pen is touching or hovering (within 0.5 s) are ignored.
  Turn it off if you want to pan/zoom with a finger while the pen is nearby.
- **Pen button = hold Space (pan):** holding the pen's side button holds Space in Figma, so pen-drag pans the canvas.
  The button is hidden from the page so it doesn't trigger a right-click menu.
- **Keep screen on while editing:** on file tabs only.
Long-press menus and haptics are disabled so holding the pen still doesn't pop up menus.
Pressure/tilt/hover are passed to Figma by the WebView as standard pointer events.

## FigCraft (Figma alternative) tab
FigCraft (github.com/storytold/figcraft) is an open-source, Rust-built Figma alternative with a WebAssembly web build you host yourself.
Settings -> FigCraft: tick **Show FigCraft tab**, paste the URL where your FigCraft web build is hosted, tap Done.
A pinned **FigCraft** tab appears next to Home. With "Load at startup" on, it loads in the background alongside Figma.
Pen, palm rejection and keyboard shortcuts (Ctrl+1/2 to jump between Home and FigCraft) work in it too.
