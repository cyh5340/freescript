# Redesign Spec & Implementation Phases

Reference from [`CLAUDE.md`](../CLAUDE.md).

---

## Canvas Modes

The app has three canvas modes, locked per session at creation:

| Mode | `CanvasMode` | Description |
|---|---|---|
| 直書模式 | `VERTICAL` | RTL column-major vertical writing (legacy default) |
| 橫書模式 | `HORIZONTAL` | LTR horizontal writing; uses shared canvas |
| 自由模式 | `FREESTYLE` | Multiple user-created `TextBoxInstance` fields on one canvas |

Sessions **cannot change mode** after creation. `EntryPageActivity` presents mode selection on app launch.

---

## Entry Page (`EntryPageActivity`)

- Top half: three mode-selection cards (直書, 橫書, 自由).
- Bottom half: 3 most recently modified sessions from `SessionManager.listSessions()`.
- Tapping a recent session → `MainActivity` with the target session ID.
- Tapping a mode card → `MainActivity` with a new session of that mode.

---

## Shared Multi-Mode Architecture (Target Design)

Goal: replace ad-hoc per-mode branches with shared services.

### Shared services needed

- **Canvas drawing** (`PoemCanvasView.kt`): one renderer for all modes. Each input field provides orientation, bounds, data, style, cursor, selection, preview. Freestyle renders each `TextBoxInstance` through the same path.
- **Keyboard/toolbar** (`MainActivity.kt`, `ViewFactory.kt`): `ghostInput`, IME, punctuation toolbar, tools panel, and keyboard/tools mutual exclusion are mode-agnostic. Toolbar queries the active field for capability.
- **Session** (`SessionManager`, `EditorViewModel`, `EditorStateModels`): common document schema — `canvasMode`, global settings, images, and a list of input fields. Legacy vertical/horizontal sessions migrate to one-field documents.
- **Style on active field**: font, size, text color, word gap apply to the active field. VERTICAL/HORIZONTAL: the single full-page field. FREESTYLE: the selected `TextBoxInstance`; style controls hidden when no box is selected.

### Current `TextBoxInstance` fields

`leftPx`, `topPx`, `colCount`, `rowCount`, `columnData`, `columnBreaks`, `fontIndex`, `fontSizeSp`, `wordGapDp`, `gridTextColor`, `inputMode`, `isHorizontal`.

### Main redundancies to eliminate

- `drawMainContent()` / `drawHorizontalContent()` / `drawInactiveBoxContent()` — same cell loop with orientation differences. Replace with one `drawField(...)` helper.
- `cellIndexAtTouch()` / `cellRect()` branch separately per mode — use one field-geometry mapper.
- `poemCanvas.isHorizontalMode`, `poemCanvas.isFreestyleMode`, `canvasMode`, `activeFreestyleBoxId` — overlapping state. Pass renderable field states instead.
- `rebuildGrid()` / `rebuildHorizontalGrid()` / `updateFreestyleCanvas()` — extract `computeCellMetrics()`, `updateCanvasField()`, `focusAfterReflow()`.
- `activateFreestyleBox()` / `deactivateFreestyleBox()` mirroring to/from global `columnData` — replace with explicit active-field reference.
- Style callbacks (`onFontSelected`, etc.) each repeat history-push + global-update + freestyle-branch — replace with `applyStyleToActiveField { }`.
- Session save arg chain across `MainActivity` → `EditorViewModel` → `SessionRepository` → `SessionManager` — use `SessionDocument` DTO (now defined in `EditorStateModels.kt`).

### Recommended refactor order

1. Define shared `InputFieldState` / `EditableFieldRef` in `EditorStateModels.kt`.
2. Migrate VERTICAL/HORIZONTAL to one field internally; preserve legacy JSON load.
3. Convert freestyle boxes to the same field model; remove `columnData` mirroring.
4. Refactor `PoemCanvasView` to one `drawField(...)` renderer.
5. Collapse style/toolbar branches into `applyStyleToActiveField`.

---

## Unfinished Freestyle Tasks

All resolved or superseded by Phase 5 completion. See Phase 5 below.

---

## Implementation Phases

### Phase 1 — Bug Fixes (complete)

- [x] Fix toolbar `buildModeHeaderRow` chip sizing (12sp, 10dp padding, `setSingleLine`).
- [x] Fix font/gap changes clipping content instead of reflowing to extra columns.

### Phase 2 — Session Metadata (complete)

- [x] `SessionManager.saveSession()` writes `wordCount`/`imageCount` to JSON.
- [x] `SessionMeta` carries `wordCount`, `imageCount`, `canvasMode`.
- [x] `SessionListActivity` shows `23字 · 2圖` subtitle per row.

### Phase 3 — Entry Page & Mode Locking (complete)

- [x] `EntryPageActivity` with three mode cards + 3 recent sessions.
- [x] `MainActivity.onCreate()` parses incoming session's `canvasMode` and hides mode-swap controls.

### Phase 4 — Horizontal & Freestyle Core (complete)

- [x] `rebuildHorizontalGrid()` + `poemCanvas.isHorizontalMode` for HORIZONTAL canvas.
- [x] `TextBoxInstance` data class + serialization in `SessionManager`.
- [x] Long-press creates a 2×2 `TextBoxInstance`; tap activates/deactivates boxes; blue border = active, grey = inactive.
- [x] Style callbacks mutate only active box in FREESTYLE; never alter global state.

### Phase 5 — Freestyle Polish (complete)

- [x] `showBoxEditor()` accessible via bottom-right corner icon (◢); confirm calls `reflowColumnData` + `placeFrontierMarker`.
- [x] Four distinct corner icons drawn in `PoemCanvasView.drawFreestyleCanvas()`: ≡ (top-left/MOVE), × (top-right/DELETE), 直/橫 (bottom-left/TOGGLE_FLOW), ◢ (bottom-right/RESIZE). `freestyleCornerActionAtTouch()` dispatches to `FreestyleCornerAction` enum.
- [x] Drag-to-move via top-left grip only: `handleFreestyleTouchEvent` ACTION_DOWN sets `freestyleDragBox` only when `freestyleCornerActionAtTouch == MOVE`.
- [x] Active box drawing clipped to box bounds: `canvas.clipRect(bx, by, bx+boxW, by+boxH)` in `drawFreestyleCanvas()`.
- [x] Tap-to-cursor inside box: ACTION_UP → `poemCanvas.cellIndexAtTouch(x, y)` → `focusCell(cellIdx)` when no corner action hits.
- [x] Per-box undo/redo: `snapshotState()` and `restoreHistoryState()` now copy `inputMode` and `isHorizontal` alongside all other box fields.
- [x] Restore all boxes inactive: `restoreHistoryState()` sets `activeTextBoxId = null`; `loadSessionFile()` sets `activeTextBoxId = null` before `applyCanvasModeLayout()`.

### Phase 6 — `MainActivity` Responsibility Split

Cohesive controllers have been extracted; `MainActivity` is now a coordinator that implements each controller's `Callbacks` interface and delegates.

**Controllers wired** (all instantiated and used in `MainActivity`):

| File | Status | Description |
|---|---|---|
| `InsertedImageController.kt` | **Wired** (`imageCtrl`) | Image lifecycle, z-ordering, two-finger pinch/pan, active-image swap |
| `SelectionController.kt` | **Wired** (`selectionCtrl`) | `isSelecting` state, handles, drag, copy/cut/paste, line/para/all selectors, selection-options popup placement (uses `visualXForCell` so it tracks horizontal word-per-cell layout) |
| `KeyboardToolbarController.kt` | **Wired** (`keyboardToolbarCtrl`) | `toolsVisible`, IME ⇄ tools-panel mutual exclusion, insets, panel collapse, `hideBottomForOverlay` |
| `ScrollCoordinator.kt` | **Wired** (`scrollCoordinator`, new — not in earlier plan) | `scrollToColumn`, `scrollToTopIfCursorInUpperView`, debounced `translateForKeyboard` with IME-animation retry |
| `LiveHorizontalEditorController.kt` | **Wired** (`liveEditorCtrl`, new — not in earlier plan) | Visible `EditText` overlay for HORIZONTAL canvas and FREESTYLE-horizontal boxes; word-per-cell commit, pre-commit visual-X wrap, action-mode "Select All" intercept |
| `ScreenshotController.kt` | **Wired** (`by lazy`) | Crop view + capture + gallery save |

**`SessionDocument` DTO** lives in `EditorStateModels.kt` and is the consolidated session-state object used by `MainActivity` / `EditorViewModel` / `SessionRepository` / `SessionManager`.

**Files in earlier plan that did not ship:**
`FreestyleController.kt`, `TextInputController.kt`, `InputFieldEditController.kt`, `CanvasModeController.kt` — extraction deferred. The functionality stayed in `MainActivity` because the freestyle/text-input/canvas-mode paths are tightly coupled to the IME/TextWatcher hot path and to per-frame rendering, and the cost of marshalling state through extra callback interfaces outweighed the readability win.

**Extraction rules (still hold):**
- Keep `onCreate`, `onStop`, `dispatchTouchEvent` in `MainActivity` (delegate work from them).
- Keep per-frame rendering inside `PoemCanvasView.kt`.
- Keep pure grid/text algorithms in `GridLogicHelper.kt`.
- Use callback interfaces or state DTOs for controllers; avoid per-keystroke / per-frame allocations.

**Do not extract:**
- `dispatchTouchEvent` itself (keep in `MainActivity`; delegate specific handling).
- `PoemCanvasView` drawing loops (refactor internally, never move out).
- Per-cell `EditText` patterns (legacy; do not revive).

### Phase 7 — Cross-cutting (in progress)

- [x] **Folders for sessions** — `SessionManager.{listFolders, createFolder, deleteFolder, renameFolder, moveSession}`; `SessionMeta.folder`; `SessionListActivity` cross-mode browsing with per-row mode chip, folder rename/delete (delete dialog enumerates contents), New Document defaults to the currently drilled-in folder.
- [x] **Mode chip per session row** — `直 / 橫 / 自` chip rendered in front of every session name; replaces the previous mode-tab filter (sessions of all modes now share one list).
- [x] **HORIZONTAL visual-width reflow** — `reflowColumnDataHorizontalVisual(newNumRows, cellSize)`: Latin word cells use `Paint.measureText`, CJK/markers/empty take `cellSize`; wraps when cumulative visual-X overflows the line's pixel width.
- [x] **HORIZONTAL pre-commit wrap fix** — `LiveHorizontalEditorController.maybeWrapToNextLine` measures only the leading Latin run; CJK / mixed-leading skip pre-wrap and rely on per-character cell-count advancement.
- [x] **About / Locale / Theme** — `AboutActivity` + `LocaleHelper.attachBaseContext`/`applyNightMode`. EntryPage exposes a ⚙ link to About; About has language toggle, dark/light toggle, "2026" + GitHub link.
- [ ] **HORIZONTAL selection / live-editor conflict** — known issue. `liveHorizontalEditor.customSelectionActionModeCallback` returns `true`, so Android's native selection toolbar can appear before canvas selection takes over. See `.claude/rules/canvas-engine.md` "Text Selection → Known HORIZONTAL gotchas".
