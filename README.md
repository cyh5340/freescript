# freescript

An Android app for composing vertical Chinese poetry. Characters flow top-to-bottom within a column; columns progress right-to-left, following traditional East Asian typesetting conventions. All editor behavior is programmatic — no XML layout changes.

## Features

- **Three canvas modes** — Vertical (RTL columns), Horizontal (LTR rows), Freestyle (free-placed text boxes)
- **Writing modes** — Sequential (continuous insert-shift) and Scatter (fixed-grid freeform)
- **Latin word buffering** in Horizontal mode: Latin characters accumulate into a single cell with real text-metric spacing; IME autocorrect and multi-char commits are handled cleanly
- **Language switching** — English and Traditional Chinese (繁體中文), persisted across sessions
- **Light / Dark theme** — system-default or user-selected, applied via AppCompatDelegate
- **Settings screen** (About) — accessible from the entry page (⚙) and the tools panel (More…)
- **Session management** — multiple documents, inline rename, search/date filters
- **Text selection** — drag handles, copy/cut/paste, select line/paragraph/all
- **Backgrounds** — solid color palettes and up to 5 inserted images with pinch/pan transform
- **Screenshot & crop** — 4-corner crop view, saved to gallery
- **Undo / Redo** — up to 50 history frames
- **Custom font** — LXGW WenKai bundled; configurable size and character spacing

## Requirements

- Android SDK 33 (compile), minimum SDK 24
- Java 8, Kotlin 1.7.20

## Build

```bash
./gradlew.bat assembleDebug
./gradlew.bat installDebug
./gradlew.bat test
./gradlew.bat connectedAndroidTest
```

## Project layout

```
app/src/main/
├── assets/fonts/
│   ├── LXGWWenKai-Regular.ttf
│   └── OFL.txt                 # SIL Open Font License 1.1
└── java/com/freescript/
    ├── MainActivity.kt          # activity coordinator
    ├── PoemCanvasView.kt        # canvas renderer
    ├── ViewFactory.kt           # programmatic UI factory
    ├── GridLogicHelper.kt       # pure data logic
    ├── EditorViewModel.kt       # undo/redo stacks
    ├── EditorStateModels.kt     # state data classes
    ├── SessionRepository.kt     # session delegate
    ├── SessionManager.kt        # JSON + file I/O
    ├── ScreenshotController.kt  # screenshot flow
    ├── InputFieldEditorView.kt  # input field resize overlay
    ├── HatchDrawable.kt         # tiled hatch drawable
    ├── SessionListActivity.kt   # cross-mode session/folder browser with rename + delete
    ├── EntryPageActivity.kt     # entry / mode selection screen
    ├── AboutActivity.kt         # language + theme settings screen
    ├── LocaleHelper.kt          # locale and night-mode persistence
    └── AppConfig.kt             # palettes, font sizes, punctuation
```

## Acknowledgements

The bundled CJK font is **LXGW WenKai (霞鶩文楷)** by **Lxgw (高翔)**,
included unmodified at `app/src/main/assets/fonts/LXGWWenKai-Regular.ttf`.

- Source repository: <https://github.com/lxgw/LxgwWenKai>
- License: [SIL Open Font License 1.1](app/src/main/assets/fonts/OFL.txt)

Many thanks to Lxgw for releasing such a beautiful font under an open
license.
