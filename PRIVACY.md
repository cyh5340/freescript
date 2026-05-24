# Privacy Policy for FreeScript (亂寫)

**Effective date:** 2026-05-24
**App:** FreeScript / 亂寫 (package: `com.freescript`)
**Developer:** cyh5340

## Summary

FreeScript does not collect, transmit, sell, or share any personal data. The app has no internet access, no user accounts, no analytics, no advertising, and no third-party tracking SDKs. Everything you write stays on your device.

## What data the app stores on your device

All app data is written to FreeScript's private application storage on your device and is not accessible to other apps. The data is removed when you uninstall the app.

| Data | Where it lives | Purpose |
|---|---|---|
| Your documents (text, layout, settings) | `filesDir/poems/[folder/]{id}.json` | Persist your work between sessions |
| Background and inserted images | `filesDir/backgrounds/` | Display the images you chose as document backgrounds |
| Last-opened session, language, theme | App SharedPreferences | Restore your last document and preferences on launch |
| Screenshots you choose to save | Your device's public **Pictures** gallery (via Android `MediaStore`) | Saved only when you tap the screenshot button and confirm |

FreeScript does not read, index, or upload any files on your device beyond what you explicitly select through Android's system file picker, and even then only the images you pick are copied into the app's private storage for display.

## Permissions

| Permission | When requested | Why |
|---|---|---|
| `WRITE_EXTERNAL_STORAGE` (Android 9 / API 28 and below only) | When you save a screenshot | Required by legacy Android to write the screenshot file to your public Pictures folder. On Android 10+ the app uses `MediaStore` and no permission is needed. |

No other runtime permissions are requested. The app does not request access to the camera, microphone, location, contacts, calendar, phone, SMS, accounts, or the internet.

## Network access

The app does **not** declare the `INTERNET` permission and makes no network requests. The "GitHub" link on the About screen, if tapped, opens the GitHub website in your device's default browser — that browsing session is governed by the browser's and GitHub's own privacy policies, not this one.

## Third parties

FreeScript does not integrate any third-party SDK, analytics platform, crash reporter, advertising network, or backend service.

## Children's privacy

FreeScript is not directed at children and does not knowingly collect any data from anyone, including children under 13.

## Data deletion

Because all data is stored locally, you can delete it in two ways:

1. Delete individual documents and folders from inside the app (long-press / trash icon).
2. Uninstall the app, or use **Settings → Apps → FreeScript → Storage → Clear Data** on your device, which permanently removes every document, image, and preference the app has stored.

## Changes to this policy

If this policy changes in a future release, the new version will replace this file in the source repository and the effective date above will be updated. There is no in-app notification because the app cannot reach the network.

## Contact

Questions about this policy: please open an issue at https://github.com/cyh5340/freescript.
