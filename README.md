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
