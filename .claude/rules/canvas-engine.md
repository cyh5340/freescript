# Canvas Engine — Architecture Deep-Dive

Reference from [`CLAUDE.md`](../../CLAUDE.md).

---

## View Hierarchy

```
FrameLayout rootFrame
|-- ImageView bgImageViews[*]          ← one per inserted image; last-inserted = topmost child
|-- LinearLayout mainLayout (vertical)
|   |-- FrameLayout scrollIndicatorContainer  ← 4dp scroll thumb; GONE during input field adjustment
|   |-- NestedScrollView mainScrollView       ← bg forced to bgColor during adjustment; → TRANSPARENT on exit
|   |   `-- LinearLayout gridContainer        ← padding = gridPaddingTop/Bottom/Left/Right
|   |       `-- HorizontalScrollView hScroll
|   |           `-- PoemCanvasView poemCanvas
|   `-- LinearLayout bottomPanel              ← built by ViewFactory.buildBottomPanel()
|       |-- HorizontalScrollView punctToolbar ← visible only while IME is open
|       `-- FrameLayout toolbar (54dp)
|           |-- LinearLayout mainBar          ← [工具 w=1] | [↩ 44dp] [↪ 44dp] | [。，、 w=1] | [截 44dp]
|           `-- LinearLayout punctBar         ← [← 54dp] | [HScrollView(punct)]
|-- LinearLayout allToolsPanel               ← Gravity.BOTTOM, height = lastKeyboardHeight
|   |-- TextView "▾" collapse
|   `-- NestedScrollView → LinearLayout
|       |-- buildFontRow: font spinner + 字號 chip + 字距 chip
|       |-- text color swatch row
|       |-- 底色 row: label + HScrollView(bg swatches)
|       |-- 插入 row: 選圖 chip + insertImageContainer
|       |-- buildModeHeaderRow: [輸入框 chip] | [mode label] [spacer] [灑花 chip] [連續 chip]
|       |-- buildFreestyleInteractRow (FREESTYLE only): [移動 chip] [調整 chip]
|       `-- 檔案 row: filename + 所有文檔 chip
|-- View startHandle / endHandle             ← blue ovals for text selection
|-- LinearLayout selectionOptionsView        ← 複製/剪下/貼上/選取整行/選取整段/全選
|-- TextView handlePasteView                 ← mini 貼上 bubble
|-- EditText ghostInput                      ← 1x1 invisible IME target (VERTICAL / SCATTER / FREESTYLE-vertical)
`-- EditText liveHorizontalEditor            ← visible overlay on focused cell (HORIZONTAL / FREESTYLE-horizontal); built by LiveHorizontalEditorController
```

**Keyboard/tools mutual exclusion:** `toolsVisible` flag; `switchToTools()` hides IME shows panel; `switchToKeyboard()` hides panel shows IME. Insets listener keeps `mainScrollView.paddingBottom` in sync with current IME height while keyboard is visible.

**Caret auto-visibility while typing:** `scheduleTranslateForKeyboard()` debounces caret translation, then `translateForKeyboard()` computes the focused cell rectangle, converts it into `mainScrollView` coordinates via `offsetDescendantRectToMyCoords`, and uses `smoothScrollBy` so the caret stays inside the currently visible viewport. A short retry window during IME animation is intentional.

**`hideBottomForOverlay()`:** hides `allToolsPanel` + `bottomPanel` without touching `toolsVisible`. Used by `showInputFieldEditor()`, `showBoxEditor()`, and `ScreenshotController`.

---

## ViewFactory / Callbacks Pattern

`ViewFactory(context, cb: Callbacks)` owns all view construction. No logic in ViewFactory — calls `cb.*` for all state reads and events.

Full callback list: `provideFontCatalogue()`, `getFontIndex()`, `getFontSizeSp()`, `getWordGapDp()`, `getSelectedTypeface()`, `getInputMode()`, `getCurrentSessionName()`, `onToolsToggle()`, `onPunctInsert()`, `onFontSelected()`, `onFontSizeChanged()`, `onWordGapChanged()`, `onBgColorSelected()`, `onInsertImageRequested()`, `onRemoveInsertedImage()`, `onTextColorSelected()`, `onModeSelected()`, `onCopySelection()`, `onCutSelection()`, `onPasteAtSelection()`, `onSelectEntireLine()`, `onSelectEntireParagraph()`, `onSelectAll()`, `onHandlePasteClicked()`, `onShowAllSessions()`, `onCollapsePanel()`, `onUndoAction()`, `onRedoAction()`, `onScreenshot()`, `onInputFieldEdit()`, `canUndo()`, `canRedo()`.

After `buildBottomPanel(dp)`, `MainActivity` copies back refs: `allToolsPanel`, `punctToolbar`, `toolsCell`, `docFileNameRef`, `fontSpinnerRef`, `fontSizeLabelRef`, `gapValueLabelRef`, `modeChipContainer`, `undoButton`, `redoButton`, `insertImageContainer`.

---

## Data Model

`columnData: MutableList<MutableList<String>>` — source of truth.
- `columnData[col][row]` is one character or `""`. Column 0 = rightmost visible column.
- `setColumnChar(col, row, ch)` grows lists as needed; always use it for cell writes.
- `clearDocumentContent()` clears `columnData` and `columnBreaks`.

`columnBreaks: MutableSet<Int>` — column indices that start after an explicit Enter break.

Cell index formula:
```
index = col * numRows + row
col   = index / numRows
row   = index % numRows
```

---

## Grid Sizing

**VERTICAL:**
```
availH      = stableMaxHeight  (mainScrollView.height − paddingTop − paddingBottom)
numRows     = floor(availH / cellSize)
numColumns  = maxOf(50, columnData.size)
canvas W    = numColumns * cellSize
canvas H    = numRows    * cellSize
```

**HORIZONTAL** (`rebuildHorizontalGrid()`):
```
availW      = stableMaxWidth   (mainScrollView.width − paddingLeft − paddingRight)
numRows     = floor(availW / cellSize)   ← chars per line
numColumns  = maxOf(50, columnData.size) ← line count
canvas W    = numRows    * cellSize      ← fits mainScrollView width
canvas H    = numColumns * cellSize      ← grows downward
```
`hScroll.layoutDirection = LTR`; `poemCanvas.isHorizontalMode = true`. Data: col = line index (top→bottom), row = char index within line (left→right).

**Cell size:**
```
fontPx   = sp→px(fontSizeSp)
gapPx    = wordGapDp * density
charSize = Paint.measureText(CJK sample) with selectedTypeface
cellSize = charSize + gapPx
```

`gridPaddingTopPx/BottomPx/LeftPx/RightPx` — per-session padding on `gridContainer`. Default: 16dp top, 0 others. Set by `InputFieldEditorView` on confirm. Persisted in JSON as `gridPadTop/Bottom/Left/Right`.

`rebuildGrid()` — for settings/session changes; dispatches to `rebuildHorizontalGrid()` (HORIZONTAL) or `updateFreestyleCanvas()` (FREESTYLE).  
`refreshGrid()` — for content edits with IME open; calls `poemCanvas.refreshContent(columnData)` without rebuilding layout.

---

## Canvas Renderer (`PoemCanvasView.kt`)

Draws characters, markers, selection highlights, composing preview, blinking cursor. Stores no canonical state.

- `cellRect(index)` and `cellIndexAtTouch(x, y)` are the geometry APIs.
- VERTICAL/FREESTYLE: canvas width = `numColumns * cellSize` (scroll math stable).
- `isHorizontalMode = true`: `cellRect` returns `Rect(row*cs, col*cs, ...)`, `onMeasure` sets W = `numRows*cs`, H = `numColumns*cs`.
- `drawHorizontalContent(canvas, cs, nr)`: x = `row*cs + cs/2`, cursor = vertical bar at cell left edge.

### IME bridges (two paths, selected by mode)

The `liveEditorActive` predicate in `MainActivity` decides which `EditText` receives focus on `focusCell()`. It is `true` whenever the active field is HORIZONTAL (HORIZONTAL canvas, or a FREESTYLE box with `isHorizontal == true`); otherwise `false`.

**`ghostInput`** — invisible 1×1 `EditText` on `rootFrame`. VERTICAL / SCATTER / FREESTYLE-vertical IME target. Holds `GHOST_SENTINEL` so hardware backspace dispatches through `TextWatcher`. `setupGhostInput()` owns: hardware backspace, Enter, IME composing preview, paste/multi-char commits, single-char typing. Disabled (`isFocusable = false`) while `liveEditorActive` so a thin 1-px ghost strip can't steal focus.

**`liveHorizontalEditor`** — visible `EditText` overlay floated on top of the focused cell, owned by `LiveHorizontalEditorController` and added to `rootFrame` once during `onCreate`. HORIZONTAL canvas and FREESTYLE-horizontal box IME target. Width = `WRAP_CONTENT` with `minWidth = cellSize` so it only intercepts touches over its own typed text, leaving surrounding canvas cells tappable.

Word-per-cell commit:
- Letters / apostrophes accumulate in the editor (mid-word).
- The first boundary character (space, punctuation, digit, CJK) triggers `commitWord(text, boundaryIdx)`: the leading Latin chunk lands in the current cell as a single multi-char cell, the boundary lands in the next cell, and any remainder reseeds the editor so multi-word pastes commit in one watcher loop.
- During IME composition (`SPAN_COMPOSING`), commit is deferred — Gboard autocorrects natively.
- CJK first char → boundary immediately → one-per-cell with no buffering.

Pre-commit visual-X wrap (`maybeWrapToNextLine`): measures only the **leading Latin run** with `Paint.measureText`. If placing that prefix at the current cell would push past `numRows * cellSize`, advance `focusedCellIndex` to the next line's first cell before the user sees the word land off-screen. Skipped when `row == 0` (already at line start; nowhere to wrap to) and skipped when the text starts with non-Latin (CJK commits one-per-cell and wraps naturally on cell count). A similar visual-width wrap runs again inside `commitWord` so the cell-count-based advance can't overflow.

Action-mode callback (`customSelectionActionModeCallback`) is installed and currently returns `true` (system selection toolbar is allowed); it intercepts "Select All" to commit any pending word and route to `selectionCtrl.selectAll(numColumns)`. This is the surface area where canvas selection and the EditText's system selection still compete.

---

## Key Helper Functions (`MainActivity.kt`)

| Function | Purpose |
|---|---|
| `withRestoring { }` | Sets `isRestoring` around programmatic mutations |
| `persistCurrentState()` | Saves session JSON + SharedPreferences |
| `advanceToNextCell(index, total)` | Focus next cell, top-scroll logic, scroll column into view |
| `postRefreshFocusColumn(index, col)` | Posts `refreshGrid` + `focusCell` + `scrollToColumn` |
| `scrollToTopIfCursorInUpperView(index)` | Scrolls to top if cursor.top is in upper 35% of `mainScrollView` |
| `refreshModeChips()` | Only place that recolors writing mode chips |
| `performInsert(newChars)` | Insert at `focusedCellIndex`; returns `lastWrittenIdx + 1` |
| `insertCharsAt(col, row, chars)` | Flat-slice insert-shift; trims trailing blanks; never crosses `columnBreaks` |
| `deletePreviousSequentialCell(col, row, index)` | SEQUENTIAL backspace; reflows; refocuses |
| `removeColumnBreak(col)` | Reverses Enter: clears `LINE_END_MARKER`, merges columns |
| `makeRoomBeforeParagraphBreakForLineEndInsert(col, row, len)` | Inserts empty columns before typing onto boundary `LINE_END_MARKER` |
| `hideBottomForOverlay()` | Hides panel + bottomPanel without changing `toolsVisible` |
| `buildEditorButtonBar(onCancel, onConfirm)` | 60dp bottom bar with Cancel/Confirm; added above overlay in z-order |
| `showInputFieldEditor()` | Scale preview + hatch effect; branches on canvasMode |
| `showBoxEditor(box)` | 4-edge resize for FREESTYLE box; no scale/hatch |
| `scrollToColumn(col)` | VERTICAL/FREESTYLE: `hScroll` horizontal; HORIZONTAL: `mainScrollView` vertical |
| `showPopupSeekbar(anchor, max, initial, fmt, onChange)` | Floating `PopupWindow` seekbar above chip (字號, 字距) |

---

## Reflow

`reflowColumnData(newNumRows)` (VERTICAL) — redistributes content when column height changes. Skipped in SCATTER mode.

`reflowColumnDataHorizontalVisual(newNumRows, cellSize)` (HORIZONTAL) — visual-width-aware variant. Streams the existing content with `null` = explicit column break, then re-places each cell using:
- Latin word cells (`item[0].code in 1..127`) → `Paint.measureText(item)` with `selectedTypeface` at the current font size.
- CJK, empty, `FRONTIER_MARKER`, `LINE_END_MARKER` → exactly `cellSize`.

Wrap when (a) `row >= newNumRows` OR (b) `row > 0 && visualX + cellWidth > newNumRows * cellSize`. The `row > 0` guard prevents an infinite empty-line cascade for a single word that's wider than the entire line.

Trigger in `rebuildGrid`:
- VERTICAL SEQUENTIAL: `reflowColumnData` fires on `numRows != newNumRows` or when `needsReflow = true`.
- VERTICAL SCATTER: never reflowed; uses `squeezeScatterOutOfRange(newNumRows)` when shrinking.
- HORIZONTAL: always `reflowColumnDataHorizontalVisual` after font/gap/grid-padding changes. Cell-count-only reflow would let Latin word cells overflow the canvas's right edge.

Reflow stream rules:
- `null` = explicit column break.
- `""` placeholders preserved inside non-empty ranges.
- Trailing empty cells excluded; explicit break columns without content still represented.

### SCATTER Squeeze (`GridLogicHelper.squeezeScatterOutOfRange`)

Called when SCATTER and `newNumRows < numRows`. Per column: collects non-blank non-marker chars from rows `≥ newNumRows`, truncates column, repacks into free slots from bottom upward. Spaces are not squeezed. Overflow characters are dropped.

---

## Focus and Cursor

`focusCell(index, showKeyboard)`:
- `focusedCellIndex` IS the insertion point; cursor drawn at top of that cell.
- Calls `scrollToTopIfCursorInUpperView(index)` — universal fix for word-wrap, Enter, paste, backspace.
- Top half of occupied cell → `focusCell(index)`; bottom half → `focusCell(index + 1)`.

---

## Input Modes

### SEQUENTIAL (連續輸入)

**Markers** (`const val` in `GridLogicHelper`):
- `FRONTIER_MARKER` (zero-width space `"​"`) — cell after last real content in the **last** paragraph.
- `LINE_END_MARKER` (`"↵"`) — row 0 of first empty column after content in **non-last** paragraphs.

`placeFrontierMarker()` is idempotent and self-correcting: locates last real cell per paragraph, demotes stale markers to `" "`, places the correct marker, gives empty non-last paragraphs `LINE_END_MARKER` at row 0.

**Empty-cell tap redirect** (`findSequentialTapTarget`): if tapped column has a marker → jump there; else walk rightward (decreasing col) within the paragraph to first marker; if paragraph empty → land at `paraStart` row 0.

- Typing → insert-shift (`performInsert`).
- DEL → `deletePreviousSequentialCell` + reflow.
- Enter → places `LINE_END_MARKER`, structural column insert for tail, shifts right.
- SCATTER→SEQUENTIAL switch → `fillGapsForSequentialMode()`.

### SCATTER (灑花輸入)

Canvas is fixed-width. No structural shift through SEQUENTIAL paths.

- Tap → exact cell.
- Empty/space cell: overwrite + advance.
- `isNotBlank()` cell (island): insert-shift same as SEQUENTIAL.
- DEL on blank/space: clear at current row, pad `""` to preserve alignment.
- Enter: writes `LINE_END_MARKER` in place + adds `columnBreaks` entry (no structural shift).
- Font/gap changes: no reflow; squeeze out-of-range when shrinking.

**Island** = maximal run of `isNotBlank()` chars in column-major order. Space breaks islands and is not part of any island.

---

## Enter / Newline

All paths route to `handleEnter()`: hardware `KEYCODE_ENTER`, soft IME action, `\n` in `TextWatcher`.

- **SEQUENTIAL**: tail extract → `LINE_END_MARKER` at cursor → `columnData.add(nextCol, tail)` → jump to `nextCol * numRows`.
- **SCATTER**: `LINE_END_MARKER` in place → `columnBreaks.add(nextCol)`.

Backspace on `LINE_END_MARKER` → `removeColumnBreak()`.

---

## Character Input

### VERTICAL / SCATTER (`ghostInput.TextWatcher.afterTextChanged` in `MainActivity`)

- Ignored when `isRestoring`, `isPreviewing`, `liveEditorActive`, or local `suppress`.
- Composing spans → grey preview in following empty cells.
- Empty raw text (backspace): island or SEQUENTIAL → `deletePreviousSequentialCell`; SCATTER blank → clear-and-back.
- SCATTER + multi-char + island → `performInsert`.
- SCATTER + multi-char + blank → overwrite + advance.
- Single char + SCATTER + island → `performInsert`.
- Single char otherwise → write cell + advance.
- SEQUENTIAL multi-char commit → insert-shift via `performInsert`.
- Paste/multi-line → distribute manually + record `columnBreaks`.

### HORIZONTAL / FREESTYLE-horizontal (`LiveHorizontalEditorController`)

- `afterTextChanged` runs a commit loop: read text → background colour swap → `maybeWrapToNextLine()` → defer if `SPAN_COMPOSING` → find first non-`[a-zA-Z']` boundary → if none, leave word in editor; otherwise `commitWord(text, boundaryIdx)`. After commit, `setText(remainder)` reseeds the editor under `isResetting = true` and the loop continues so pasted multi-word text commits in one pass.
- `commitWord(text, boundaryIdx)`: separates `wordPart` (Latin chunk) and `boundaryChar`; runs a second visual-width wrap on `wordPart`; writes the word into the focused cell as a single multi-char string, the boundary into the next cell; advances `focusedCellIndex` past both; calls `updatePosition()` to reposition the overlay.
- Backspace at empty editor: pops the previous cell — multi-char cell drops its last char; single-char clears + retreats focus.
- Enter → commits any pending text then `cb.handleEnter()` (routes through the same `MainActivity.handleEnter()` as the other modes).

---

## Punctuation

`insertPunct(punct)`:
- Empty cell OR (SCATTER + blank): write directly + advance under `withRestoring`.
- Island OR SEQUENTIAL occupied: `performInsert` + `postRefreshFocusColumn`.

---

## Text Selection (`SelectionController`)

Selection lives entirely in `SelectionController`; `MainActivity` only forwards `dispatchTouchEvent` events to it.

Double-tap → `selectionCtrl.enterSelectionMode(index)`: hides IME, `isSelecting = true`, places both handles at index. `MainActivity.onSelectionEntered()` additionally clears + hides `liveHorizontalEditor` in HORIZONTAL mode so the overlay's own action mode can't compete with the canvas selection UI.

**Handles**: blue ovals. `positionHandleAt(handle, index, atTop)` anchors at `visualXForCell(index)` / `visualEndXForCell(index)` so the handles align with the rendered text edge in HORIZONTAL mode (where Latin word cells render narrower than `cellSize`). VERTICAL falls back to `cellRect.left/right`.

**Drag** in `dispatchTouchEvent`: DOWN → hit-test 36dp radius → `activeDragHandle`. MOVE → `selectionCtrl.fastCellIndexAtPoint` → `postOnAnimation` → `updateDragSelection`. UP with no drag → `handlePasteView` bubble.

`fastCellIndexAtPoint` uses `PoemCanvasView.cellIndexAtTouch` (grid-X-based) and clamps to the last occupied column.

`updateHighlightDiff(newFrom, newTo)`: repaints only boundary cells (not full redraw).

**Known HORIZONTAL gotchas:**
- `cellIndexAtTouch` resolves by *grid* X (`row = touchX / cellSize`) while handles anchor at `visualXForCell` (cumulative `measureText`). On rows of short Latin words the rendered text ends well before the grid extent, so dragging where you see the text snaps to a different cell index — jittery during drag.
- The live editor's `customSelectionActionModeCallback` returns `true`, so a double-tap that lands on the visible `EditText` overlay can surface Android's native selection action mode (Cut/Copy/Paste bar) before the canvas selection takes over. `onSelectionEntered` hides the overlay only *after* selection has been entered.

---

## Composing Preview

- `showComposingPreview(text, startIndex)` — grey chars in following empty cells.
- `clearComposingPreview()` — restores `gridTextColor`.
- `isPreviewing` suppresses watcher side effects.

---

## Backgrounds and Inserted Images

`applyBackground(color)`: sets `bgColor`, `rootFrame.setBackgroundColor(color)`. Does NOT remove images.

`applyInsertedImage(uri)` (max `MAX_INSERTED_IMAGES = 5`):
- Copies to `filesDir/backgrounds/{sessionId}_{timestamp}.bg`.
- Appends to `insertedImages`, marks new entry active.
- `syncActiveImageFromList()` re-renders all overlay layers.

**Image z-ordering**: `renderInsertedImages` adds with `addView(view, bgImageViews.size)`. `activateImageAt(index)` removes + re-inserts at `bgImageViews.size - 1`. `bgImageViews` list order is never changed.

Two-finger pinch/pan → `findTouchedImageIndex` → `activateImageAt` → transform. Matrix stored per `InsertedImageState` (9 floats). `bgImageUri`/`bgImageMatrix` are runtime cache for active image only.

---

## Input Field Editor (`InputFieldEditorView`)

Full-screen overlay added to `rootFrame`. Cancel/Confirm buttons are external (`buildEditorButtonBar()`, added after overlay for higher z-order).

**Modes:**
- **2-edge vertical** (`fourEdge=false, leftRightOnly=false`): top/bottom handles only. Left/right fixed to 0/screenWidth.
- **2-edge horizontal** (`leftRightOnly=true`): left/right handles only. Top/bottom fixed. Confirm sets `gridPaddingLeftPx`/`RightPx`.
- **4-edge** (`fourEdge=true`): all four edges. Used by `showBoxEditor(box)` for FREESTYLE boxes. No hatch/scale effect.

**Entry effect** (VERTICAL/HORIZONTAL via `showInputFieldEditor()`): `mainLayout` scales by `PREVIEW_SCALE`, `rootFrame.background = HatchDrawable`, `mainScrollView.setBackgroundColor(bgColor)`, scroll indicator GONE. Restore: `mainScrollView → TRANSPARENT`, `rootFrame → bgColor`, scale reset, scroll indicator restored.

**VERTICAL confirm**: `gridPaddingTopPx = top − scrollTop`, `gridPaddingBottomPx = mainScrollView.height − (bottom − scrollTop)`. Recalculates `stableMaxHeight`, calls `rebuildGrid()` + `saveState()`.

**FREESTYLE confirm**: converts screen coords → canvas-local; updates `box.leftPx/topPx/colCount/rowCount`; calls `poemCanvas.updateData/updateFreestyleBoxes/persistCurrentState()`.

---

## Sessions

SharedPreferences: `poem_editor_state`. Files: `filesDir/poems/[folder/]{id}.json` — sessions live at the root of `poems/` or inside a single-level subdirectory (= folder name). Folder paths are mode-agnostic; any canvas mode can live in any folder.

```json
{
  "id": "uuid", "name": "Document", "lastAccessed": 0,
  "canvasMode": "HORIZONTAL",
  "columnData": [["..."]], "columnBreaks": [1, 3],
  "fontIndex": 0, "fontSizeSp": 24.0, "wordGapDp": 0.0,
  "gridTextColor": -16777216, "bgColor": -1,
  "bgImageUri": "", "bgImageMatrix": [1,0,0,0,1,0,0,0,1],
  "insertedImages": [{"uri": "...", "matrix": [...]}],
  "activeImageIndex": 0, "inputMode": "SEQUENTIAL",
  "gridPadTop": 42, "gridPadBottom": 0, "gridPadLeft": 0, "gridPadRight": 0,
  "textBoxes": [{"id":"...", "leftPx":0.0, "topPx":0.0, "colCount":2, "rowCount":2, "isHorizontal":false, ...}]
}
```

`bgImageUri`/`bgImageMatrix` retained for legacy compatibility. `gridPad*` default to 16dp top / 0 others when absent. `textBoxes` only populated when `canvasMode == "FREESTYLE"`.

`SessionMeta`: `id`, `name`, `lastAccessed`, `wordCount`, `imageCount`, `canvasMode`, `folder` — computed by `SessionManager.listSessions()` from JSON + file path, no extra I/O. The `folder` field is the directory name (empty string = root).

Folder API on `SessionManager`:
- `listFolders(filesDir)` — all folder names under `poems/`.
- `createFolder(filesDir, name)` — idempotent `mkdirs`.
- `deleteFolder(filesDir, name)` — recursive; removes the folder and every session inside.
- `renameFolder(filesDir, oldPath, newPath)` — refuses to clobber an existing folder.
- `moveSession(filesDir, id, targetFolder)` — moves the JSON file between directories.

Other key functions: `saveSession(filesDir, doc, folder)`, `loadSessionFile(id)` (saves current → loads target → applies settings → rebuilds), `ensureDefaultSession()`, `updateToolbarSessionName()`.

---

## Session DTO

`SessionDocument` (in `EditorStateModels.kt`) is the single consolidated session-state object that replaces the long parameter chain across `MainActivity → EditorViewModel → SessionRepository → SessionManager`. All four session layers accept/return a `SessionDocument` once wired (Phase 6 Priority 3). Fields mirror the session JSON schema exactly.

## Undo / Redo

`EditorHistoryState` snapshots: `columnData`, `columnBreaks`, `fontIndex`, `fontSizeSp`, `wordGapDp`, `gridTextColor`, `bgColor`, `inputMode`, `insertedImages`, `activeImageIndex`, `textBoxes`.

`undoStack`/`redoStack`: `ArrayDeque<EditorHistoryState>`, max 50. `pushHistory()` at direct user-action entry points. `performUndo/Redo` swap snapshots, call `restoreHistoryState(...)`. Session load / new session clears both stacks. `updateUndoRedoButtons()`: `text_dark` (enabled) / `text_hint` (disabled).

---

## Screenshot (`ScreenshotController`)

Initialized `by lazy` in `MainActivity`. `takeScreenshot()` delegates to controller.

Flow:
1. Hide transient views; stop cursor blink.
2. Dismiss soft keyboard; wait for `OnGlobalLayoutListener` to settle.
3. `hideBottomForOverlay()` → show `ScreenshotCropView` (4-corner drag, dimmed outside, ↵ toggle).
4. User adjusts crop → 確認 or 取消.
5. Confirm: hide crop view, set `poemCanvas.hideLineEndMarkers`, call `captureView(rootFrame, cropL, cropT, cropW, cropH)`, save via `saveToGallery`.
6. Restore: `bottomPanel`, cursor blink, `allToolsPanel` (via `toolsVisible`), selection handles.

API 29+: `MediaStore.Images`. API 24–28: `Environment.DIRECTORY_PICTURES` + `MediaScannerConnection` + `WRITE_EXTERNAL_STORAGE`.
