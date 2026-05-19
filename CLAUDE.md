# poemeditor

Android app composing text in vertical RTL column order. Characters flow top-to-bottom in a column; columns progress right-to-left. All active UI is programmatic — no XML layout changes for editor behavior.

SDK: compile 33, min 24, Java 8, Kotlin 1.7.20.

## File Layout

```
app/src/main/
|-- assets/fonts/LXGWWenKai-Regular.ttf
|-- java/com/poemeditor/
|   |-- MainActivity.kt          # activity coordinator: TextWatcher, selection, images, keyboard insets
|   |-- PoemCanvasView.kt        # canvas renderer: characters, cursor, selection, composing preview
|   |-- ViewFactory.kt           # programmatic UI factory; Callbacks bridge to MainActivity
|   |-- GridLogicHelper.kt       # pure data logic: markers, reflow, insertCharsAt, scatter squeeze
|   |-- EditorViewModel.kt       # undo/redo stacks + thin session wrappers
|   |-- EditorStateModels.kt     # InsertedImageState + EditorHistoryState
|   |-- SessionRepository.kt     # one-method-each delegate over SessionManager
|   |-- SessionManager.kt        # JSON conversion + file I/O; SessionMeta carries wordCount + imageCount
|   |-- ScreenshotController.kt  # screenshot flow + ScreenshotCropView
|   |-- InputFieldEditorView.kt  # full-screen overlay for resizing input field edges
|   |-- HatchDrawable.kt         # tiled grey "/" hatch Drawable
|   |-- SessionListActivity.kt   # session list with search/date filters + inline rename
|   |-- EntryPageActivity.kt     # first screen: mode selection + 3 recent sessions
|   `-- AppConfig.kt             # palettes, font sizes, punctuation constants
`-- res/
    |-- values/colors.xml        # fixed UI chrome colors
    `-- values/themes.xml
```

## Build

```bash
./gradlew.bat assembleDebug
./gradlew.bat installDebug
./gradlew.bat test
./gradlew.bat connectedAndroidTest
```

## Critical Invariants

- **No XML for active editor behavior** — all layout mutations are programmatic.
- **Never rebuild grid on keyboard open/close** — use `refreshGrid()` for IME-open content edits; `rebuildGrid()` only for settings/session changes. `stableMaxHeight`/`stableMaxWidth` captured once when IME is absent.
- **`withRestoring`** wraps all programmatic text/model mutations.
- **Mode routing gate** — SCATTER must not enter SEQUENTIAL insert-shift unless the island check (`isNotBlank()`) explicitly allows it. Most regressions trace to this.
- **`ghostInput` is IME-only** — never canonical document state. `PoemCanvasView` is the only visible renderer; never reintroduce per-cell `EditText`.
- **Markers live in `GridLogicHelper`** — `FRONTIER_MARKER`/`LINE_END_MARKER` are `const val` there. Never redefine in `MainActivity` or `PoemCanvasView`.
- **Pure data logic belongs in `GridLogicHelper`** — `MainActivity` keeps thin wrappers. Scrolling coordination (`scrollToColumn`, `scrollToTopIfCursorInUpperView`) stays in `MainActivity`.
- **`hideBottomForOverlay()` must not change `toolsVisible`** — screenshot and input-field editor both rely on the flag for restoration.
- **`insertCharsAt` trims only trailing blanks** — internal blanks (gap-fill spaces) are preserved in position.
- **`bgImageViews` order never changes** — z-order via `rootFrame.removeView`/`addView` only; list stays 1-to-1 with `insertedImages`.
- **`applyBackground(color)` never removes inserted images** — sets `bgColor` and `rootFrame.background` only.
- **`mainScrollView` background → `TRANSPARENT` after `showInputFieldEditor()` dismiss** — failing to restore causes wrong background to persist.
- **`pushHistory()` at direct user-action entry points only** — avoid duplicate frames.
- **ViewFactory interface names must not clash with Kotlin property getters** — use `provide*` prefix (e.g. `provideFontCatalogue()`).
- **`numColumns = maxOf(MAX_COLUMNS, columnData.size)` always** — never go below `MAX_COLUMNS = 50`.

## Reference Documents

- **Architecture & engine deep-dive**: [`.claude/rules/canvas-engine.md`](.claude/rules/canvas-engine.md)
- **Redesign spec & implementation phases**: [`docs/re-design.md`](docs/re-design.md)
