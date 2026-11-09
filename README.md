# Figma web wrapper (Android)

Personal-use multi-tab WebView wrapper around figma.com. Not affiliated with Figma.

## Build
1. Push this folder to a GitHub repo.
2. Actions tab -> "Build APK" -> download the `figma-wrapper-debug-apk` artifact.
3. Unzip and install app-debug.apk.

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
| F5 | Reload current tab |

All other keys (V, R, F, Ctrl+C/Z/D, etc.) pass through to the Figma editor,
which handles its own shortcuts. To change the tab shortcuts, edit `shortcutFor()` in MainActivity.kt.

## Pen / stylus (tested on nothing yet - built for Xiaomi Focus Pen Pro style active pens)
Tap the pen icon at the top right to open Pen settings:
- **Palm rejection:** finger touches that start while the pen is touching or hovering (within 0.5 s) are ignored.
  Turn it off if you want to pan/zoom with a finger while the pen is nearby.
- **Pen button = hold Space (pan):** holding the pen's side button holds Space in Figma, so pen-drag pans the canvas.
  The button is hidden from the page so it doesn't trigger a right-click menu.
- **Keep screen on while editing:** on file tabs only.
Long-press menus and haptics are disabled so holding the pen still doesn't pop up menus.
Pressure/tilt/hover are passed to Figma by the WebView as standard pointer events.
