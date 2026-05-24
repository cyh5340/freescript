# freescript

Android app composing text in three canvas modes: VERTICAL (RTL column-major, legacy default), HORIZONTAL (LTR line-major), and FREESTYLE (per-box, vertical or horizontal per box). Mode is locked per session at creation. All active UI is programmatic — no XML layout changes for editor behavior.

SDK: compile 33, min 24, Java 8, Kotlin 1.7.20.

## File Layout

```
app/src/main/
|-- assets/fonts/LXGWWenKai-Regular.ttf
|-- java/com/freescript/
|   |-- MainActivity.kt                    # coordinator: dispatchTouchEvent, TextWatcher, lifecycle; owns and wires all controllers
|   |-- PoemCanvasView.kt                  # canvas renderer: characters, cursor, selection, composing preview (vertical + horizontal + freestyle)
|   |-- ViewFactory.kt                     # programmatic UI factory; Callbacks bridge to MainActivity
|   |-- GridLogicHelper.kt                 # pure data logic: markers, reflow, insertCharsAt, scatter squeeze
|   |-- EditorViewModel.kt                 # undo/redo stacks + thin session wrappers
|   |-- EditorStateModels.kt               # InsertedImageState, EditorHistoryState, SessionDocument DTO, TextBoxInstance
|   |-- SessionRepository.kt               # one-method-each delegate over SessionManager
|   |-- SessionManager.kt                  # JSON + file I/O; SessionMeta (wordCount/imageCount/canvasMode); folders
|   |-- ScreenshotController.kt            # screenshot flow + ScreenshotCropView
|   |-- InsertedImageController.kt         # inserted-image lifecycle, z-order, two-finger transform
|   |-- SelectionController.kt             # selection state, handles, clipboard, line/para/all selectors
|   |-- KeyboardToolbarController.kt       # IME ⇄ tools-panel mutual exclusion, insets, collapse, hideBottomForOverlay
|   |-- ScrollCoordinator.kt               # scrollToColumn / scrollToTopIfCursorInUpperView / translateForKeyboard
|   |-- LiveHorizontalEditorController.kt  # EditText overlay for HORIZONTAL canvas + FREESTYLE-horizontal boxes
|   |-- InputFieldEditorView.kt            # full-screen overlay for resizing input field edges
|   |-- HatchDrawable.kt                   # tiled grey "/" hatch Drawable
|   |-- SessionListActivity.kt             # cross-mode session/folder browser: per-row mode chip, folder rename/delete (lists contents)
|   |-- EntryPageActivity.kt               # first screen: mode-selection cards + 3 recent sessions + ⚙ link to About
|   |-- AboutActivity.kt                   # language + theme + GitHub link
|   |-- LocaleHelper.kt                    # locale + night-mode persistence (attachBaseContext wrapper)
|   `-- AppConfig.kt                       # palettes, font sizes, punctuation constants
`-- res/
    |-- values/colors.xml                  # fixed UI chrome colors
    |-- values/strings.xml                 # English strings (zh-rTW mirror in values-zh-rTW/)
    |-- values/themes.xml
    `-- drawable/ic_github.xml             # GitHub mark vector (About page)
```

### Controller wiring

`MainActivity` implements every controller's `Callbacks` interface and owns one instance of each:

| Controller | What it owns | When MainActivity delegates |
|---|---|---|
| `InsertedImageController` (`imageCtrl`) | image list, z-order, pinch/pan matrix | image add/remove/transform; `dispatchTouchEvent` two-finger gesture |
| `SelectionController` (`selectionCtrl`) | `isSelecting`, handles, selection options popup, clipboard ops | double-tap, handle drag, copy/cut/paste, select line/para/all |
| `KeyboardToolbarController` (`keyboardToolbarCtrl`) | `toolsVisible`, IME/tools switching, insets, panel collapse | `switchToTools/Keyboard`, `hideBottomForOverlay` |
| `ScrollCoordinator` (`scrollCoordinator`) | scroll math + caret-into-view debounce | `scrollToColumn`, `scrollToTopIfCursorInUpperView`, `translateForKeyboard` |
| `LiveHorizontalEditorController` (`liveEditorCtrl`) | the visible `EditText` overlay for HORIZONTAL / FREESTYLE-horizontal | TextWatcher for word-per-cell typing, pre-commit wrap, action-mode intercept |
| `ScreenshotController` (`screenshotController`, `by lazy`) | crop view, capture, gallery save | `onScreenshot` |

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
- **Two IME targets, by mode** — VERTICAL / SCATTER / FREESTYLE-vertical use the invisible `ghostInput` (1×1 EditText, holds `GHOST_SENTINEL` for backspace detection). HORIZONTAL canvas and FREESTYLE-horizontal boxes use the visible `LiveHorizontalEditorController.editor` floated over the focused cell so the IME can compose/autocorrect word-per-cell. `liveEditorActive` selects between them; never let both consume input.
- **`PoemCanvasView` is the only visible cell renderer** — never reintroduce per-cell `EditText`.
- **HORIZONTAL reflow is visual-width-aware** — `reflowColumnDataHorizontalVisual(newNumRows, cellSize)` measures Latin word-per-cell content with `Paint.measureText`; CJK / markers / empty cells take exactly `cellSize`. Wrap when cumulative visual-X + next cell width would exceed `newNumRows * cellSize`. Do not call the cell-count-only `GridLogicHelper.reflowColumnData` for HORIZONTAL.
- **HORIZONTAL pre-commit wrap measures only the leading Latin run** — in `LiveHorizontalEditorController.maybeWrapToNextLine`, use `text.takeWhile { it in 'a'..'z' || ... }`. Measuring a mixed/CJK string treats it as one word and pre-wraps too aggressively; CJK commits one-per-cell via the boundary path and wraps naturally on cell count.
- **Markers live in `GridLogicHelper`** — `FRONTIER_MARKER`/`LINE_END_MARKER` are `const val` there. Never redefine in `MainActivity` or `PoemCanvasView`.
- **Pure data logic belongs in `GridLogicHelper`** — `MainActivity` keeps thin wrappers. Scroll math lives in `ScrollCoordinator`; selection state in `SelectionController`; tools/IME switching in `KeyboardToolbarController`; image lifecycle in `InsertedImageController`; live-editor lifecycle in `LiveHorizontalEditorController`.
- **Caret visibility while typing is viewport-based** — `ScrollCoordinator.translateForKeyboard()` must keep the focused caret rectangle visible in `mainScrollView` coordinates (`offsetDescendantRectToMyCoords` + `smoothScrollBy`) and may retry during IME animation. Do not gate this path only on IME visibility booleans.
- **`hideBottomForOverlay()` must not change `toolsVisible`** — screenshot and input-field editor both rely on the flag for restoration.
- **`insertCharsAt` trims only trailing blanks** — internal blanks (gap-fill spaces) are preserved in position.
- **`bgImageViews` order never changes** — z-order via `rootFrame.removeView`/`addView` only; list stays 1-to-1 with `insertedImages`.
- **`applyBackground(color)` never removes inserted images** — sets `bgColor` and `rootFrame.background` only.
- **`mainScrollView` background → `TRANSPARENT` after `showInputFieldEditor()` dismiss** — failing to restore causes wrong background to persist.
- **`pushHistory()` at direct user-action entry points only** — avoid duplicate frames.
- **ViewFactory interface names must not clash with Kotlin property getters** — use `provide*` prefix (e.g. `provideFontCatalogue()`).
- **`numColumns = maxOf(MAX_COLUMNS, columnData.size)` always** — never go below `MAX_COLUMNS = 50`.
- **Canvas mode is locked per session** — set at creation in `EntryPageActivity` / `SessionListActivity`. `SessionDocument.canvasMode` is the authority; the editor never offers a mode swap on an existing session.
- **Folders are mode-agnostic** — `SessionMeta.folder` is a directory under `poems/`; sessions of any canvas mode can live in the same folder. `SessionListActivity` no longer filters by mode.

## Reference Documents

- **Architecture & engine deep-dive**: [`.claude/rules/canvas-engine.md`](.claude/rules/canvas-engine.md)
- **Redesign spec & implementation phases**: [`docs/re-design.md`](docs/re-design.md)
