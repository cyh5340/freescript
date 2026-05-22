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

`MainActivity` has grown to ~2,800+ lines. Extract cohesive controllers.

**Controller files created** (in `app/src/main/java/com/freescript/`):

| File | Status | Description |
|---|---|---|
| `InsertedImageController.kt` | **Skeleton ready** — full `Callbacks` interface + method bodies extracted | Image lifecycle, z-ordering, avatar panel |
| `SelectionController.kt` | **Skeleton ready** — full `Callbacks` interface + method bodies extracted | Handles, drag, copy/cut/paste, highlight diff |
| `KeyboardToolbarController.kt` | **Skeleton ready** — full `Callbacks` interface + method bodies extracted | `switchToTools`, `switchToKeyboard`, `hideBottomForOverlay` |
| `FreestyleController.kt` | **Interface stub** — `Callbacks` defined; method bodies need active-field model first | Touch, box create/activate/deactivate, `showBoxEditor` |
| `TextInputController.kt` | **Interface stub** — `Callbacks` defined; hot path, move last | `ghostInput` setup, composing preview, typing orchestration |
| `InputFieldEditController.kt` | **Interface stub** — `Callbacks` defined | `showInputFieldEditor`/`showBoxEditor` coordinate math |
| `CanvasModeController.kt` | **Interface stub** — `Callbacks` defined | `applyCanvasModeLayout`, `rebuildHorizontalGrid`, `updateModeChipVisibility` |

**`SessionDocument` DTO** added to `EditorStateModels.kt` — replaces the long parameter chain across `MainActivity`/`EditorViewModel`/`SessionRepository`/`SessionManager`.

**Extraction rules:**
- Keep `onCreate`, `onStop`, `dispatchTouchEvent` in `MainActivity` (delegate work from them).
- Keep per-frame rendering inside `PoemCanvasView.kt`.
- Keep pure grid/text algorithms in `GridLogicHelper.kt`.
- Use callback interfaces or state DTOs for controllers; avoid per-keystroke / per-frame allocations.

**Remaining wiring steps (require compile verification):**

1. **Wire Priority-1 controllers**: have `MainActivity` implement `InsertedImageController.Callbacks`, `SelectionController.Callbacks`, `KeyboardToolbarController.Callbacks`. Replace existing private method bodies with controller delegation calls.
2. **Introduce active-field model**: add `InputFieldState` / `EditableFieldRef` to `EditorStateModels.kt`. Migrate VERTICAL/HORIZONTAL to one-field documents.
3. **Wire `FreestyleController`**: remove `activateFreestyleBox`/`deactivateFreestyleBox` global `columnData` mirroring; replace with active-field reference.
4. **Wire `InputFieldEditController`** and **`CanvasModeController`**.
5. **Wire `TextInputController`** last — hot path, requires zero allocations in `afterTextChanged`.
6. **Wire `SessionDocument` DTO** through all four session layers; replace long parameter chains.

**Do not extract:**
- `dispatchTouchEvent` itself (keep in `MainActivity`; delegate specific handling).
- `PoemCanvasView` drawing loops (refactor internally, never move out).
- Per-cell `EditText` patterns (legacy; do not revive).
