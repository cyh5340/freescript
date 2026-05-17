# poemeditor

Android app for composing text in vertical, right-to-left column order. Characters flow top-to-bottom inside a column; columns progress right-to-left.

All active UI is programmatic in `MainActivity.kt`.

## Project Layout

```text
app/src/main/
|-- assets/fonts/LXGWWenKai-Regular.ttf
|-- java/com/poemeditor/
|   |-- MainActivity.kt                  # activity coordinator: TextWatcher, selection overlay, image gestures, keyboard insets
|   |-- PoemCanvasView.kt                # canvas renderer for characters, cursor, selection, composing preview
|   |-- ViewFactory.kt                   # programmatic UI factory; Callbacks bridge to MainActivity
|   |-- GridLogicHelper.kt               # pure data logic: FRONTIER_MARKER/LINE_END_MARKER constants, setColumnChar, reflowColumnData, insertCharsAt, placeFrontierMarker, fillGapsForSequentialMode, findSequentialTapTarget
|   |-- EditorViewModel.kt               # undo/redo stacks + thin save/load/ensureDefault wrappers over SessionRepository
|   |-- EditorStateModels.kt             # InsertedImageState + EditorHistoryState
|   |-- SessionRepository.kt             # one-method-each delegate over SessionManager (save/load/ensureDefault)
|   |-- SessionManager.kt                # JSON conversion + file I/O helpers
|   |-- ScreenshotController.kt          # screenshot flow + ScreenshotCropView (crop overlay); all in one file
|   |-- SessionListActivity.kt           # full session list with search/date filters + inline rename
|   `-- AppConfig.kt                     # user-selectable constants: palettes/font sizes/punctuation
`-- res/
    |-- values/themes.xml
    |-- values/colors.xml                # fixed UI chrome colors (panel_bg, chip_active, selection_highlight, etc.)
    `-- values-night/themes.xml
```

SDK: compile 33, min 24, Java 8, Kotlin 1.7.20.

## Current View Hierarchy

```text
FrameLayout rootFrame
|-- ImageView bgImageViews[*]
|   `-- One view per inserted image, all added at child index 0..n behind editor UI
|-- LinearLayout mainLayout vertical
|   |-- NestedScrollView mainScrollView
|   |   `-- LinearLayout gridContainer
|   |       `-- HorizontalScrollView hScroll
|   |           `-- PoemCanvasView poemCanvas  ← draws all MAX_COLUMNS columns, cursor, selection, preview text
|   `-- LinearLayout bottomPanel        ← built by ViewFactory.buildBottomPanel()
|       |-- divider
|       |-- HorizontalScrollView punctToolbar    ← visible only while IME is open
|       `-- FrameLayout toolbar, 54dp, white
|           |-- LinearLayout mainBar             ← default visible: 工具 | divider | 標點
|           `-- LinearLayout punctBar            ← shown when 標點 tapped: ← | divider | HScrollView(punct)
|-- LinearLayout allToolsPanel                   ← Gravity.BOTTOM overlay, height = lastKeyboardHeight
|   |-- TextView "▾" collapse button
|   `-- NestedScrollView
|       `-- LinearLayout content (vertical)
|           |-- buildFontRow (no label): font spinner + 字號 chip + 字距 chip, all inline
|           |-- text color swatch row (no label)
|           |-- 底色 inline row: label + HScrollView(bg color swatches)
|           |-- 插入 inline row: 選圖 chip + avatar list (tap avatar = select active, × = delete)
|           |-- 輸入模式 inline row: 灑花輸入 / 連續輸入 chips
|           `-- 檔案 inline row: filename + 所有文檔 chip
|-- View startHandle                             ← blue oval, positioned by positionHandleAt()
|-- View endHandle                               ← blue oval, positioned by positionHandleAt()
|-- LinearLayout selectionOptionsView            ← 複製/剪下/貼上/選取整行/選取整段/全選, shown left of selection
|-- TextView handlePasteView                     ← mini 貼上 bubble shown on handle tap (no drag)
`-- EditText ghostInput                          ← 1x1 invisible IME target; all typed text is routed through MainActivity
```

**Keyboard / tools mutual exclusion:**
- `toolsVisible` flag tracks which is active.
- `switchToTools()`: hides IME, shows `allToolsPanel` at `lastKeyboardHeight`.
- `switchToKeyboard()`: hides `allToolsPanel`, shows IME.
- Cell touch while `toolsVisible`: dismisses panel before restoring keyboard.
- Insets listener: force-hides `allToolsPanel` if keyboard appears.
- Both paths pre-set `mainScrollView.paddingBottom` before showing so the grid never jumps.

**Toolbar bar switching (標點):**
- `mainBar` and `punctBar` are sibling children of the `FrameLayout toolbar`. Only one is `VISIBLE` at a time.
- Tapping 標點 hides `mainBar`, shows `punctBar`.
- Tapping ← in `punctBar` reverses. No state flag needed — purely view visibility.

**mainBar layout (left to right):**
`[工具 w=1] | [↩ 44dp] [↪ 44dp] | [。，、 w=1] | [截 44dp]`
- ↩/↪ are undo/redo icon buttons; dimmed via `text_hint` when stack is empty.
- 截 triggers screenshot of content area (excludes toolbar).
- Session name is shown in the tools panel 檔案 inline row (`docFileNameRef`), not in the toolbar.

**Grid height stability:**
- `stableMaxHeight` is captured once in `OnGlobalLayoutListener` when the IME is absent, before `rebuildGrid` is ever called.
- All `rebuildGrid` calls use `stableMaxHeight` as `availH`, so `numRows` never changes regardless of keyboard or panel state.
- `rebuildGrid(isInitialBoot = true)` suppresses `focusCell`/`translateForKeyboard` on first build to prevent unwanted keyboard popup.

## ViewFactory / Callbacks Pattern

`ViewFactory(context, cb: Callbacks)` owns all view construction. `MainActivity` implements `ViewFactory.Callbacks`.

- **No logic lives in ViewFactory** — it calls `cb.*` for all state reads and event responses.
- `ViewFactory.Callbacks` interface methods: `provideFontCatalogue()`, `getFontIndex()`, `getFontSizeSp()`, `getWordGapDp()`, `getSelectedTypeface()`, `getInputMode()`, `getCurrentSessionName()`, `onToolsToggle()`, `onPunctInsert()`, `onFontSelected()`, `onFontSizeChanged()`, `onWordGapChanged()`, `onBgColorSelected()`, `onInsertImageRequested()`, `onRemoveInsertedImage()`, `onTextColorSelected()`, `onModeSelected()`, `onCopySelection()`, `onCutSelection()`, `onPasteAtSelection()`, `onSelectEntireLine()`, `onSelectEntireParagraph()`, `onSelectAll()`, `onHandlePasteClicked()`, `onShowAllSessions()`, `onCollapsePanel()`, `onUndoAction()`, `onRedoAction()`, `onScreenshot()`, `canUndo()`, `canRedo()`.
- After `viewFactory.buildBottomPanel(dp)`, `MainActivity` copies back refs: `allToolsPanel`, `punctToolbar`, `toolsCell`, `docFileNameRef`, `fontSpinnerRef`, `fontSizeLabelRef`, `gapValueLabelRef`, `modeChipContainer`, `undoButton`, `redoButton`, `insertImageContainer`.
- Selection views are also built via ViewFactory: `buildSelectionHandle(dp)`, `buildSelectionOptionsView(dp)`, `buildHandlePasteView(dp)`.
- **JVM clash rule**: interface method names must not match Kotlin property getter names. `provideFontCatalogue()` (not `getFontCatalogue()`) avoids clash with the `fontCatalogue` property's auto-generated getter.

## Data Model

`columnData: MutableList<MutableList<String>>` is the source of truth.

- `columnData[col][row]` is one character or `""`.
- Column 0 is the rightmost visible column.
- `PoemCanvasView` renders from `columnData`; there are no per-cell `EditText` views.
- `ghostInput` is only the IME bridge. Its sentinel/text is not canonical document state.
- `setColumnChar(col, row, ch)` grows lists as needed and should be used for cell writes.
- `clearDocumentContent()` clears `columnData` and `columnBreaks`.

`columnBreaks: MutableSet<Int>` stores explicit Enter/newline breaks. A value means that column starts after an explicit break.

Cell index formula:

```text
index = col * numRows + row
col = index / numRows
row = index % numRows
```

## Grid Sizing

```text
fontPx   = sp -> px(fontSizeSp)
gapPx    = wordGapDp * density
charSize = Paint.measureText(CJK sample char) using selectedTypeface
cellSize = charSize + gapPx
availH   = stableMaxHeight  (locked at startup; never varies with keyboard)
numRows  = floor(availH / cellSize)
gridHeight = numRows * cellSize
numColumns = MAX_COLUMNS = 50  (always; grid skeleton is always full width for accurate scrollbar)
```

`rebuildGrid()` computes this and calls `poemCanvas.updateData(...)` so the canvas can relayout/redraw. It is for settings/session changes, not keyboard open/close. Style changes (font, size, gap, color) only fire while the tools panel is open, where IME is closed by design — so the rebuild's IME interruption is not a concern.

`refreshGrid()` refreshes markers/composing preview and calls `poemCanvas.refreshContent(columnData)` without rebuilding layout. Prefer it for content changes so the IME does not flicker.

## Canvas Renderer And IME

The editor now uses a single custom canvas view plus an invisible IME target.

### `PoemCanvasView.kt`

- Draws characters, line-end markers, selection highlights, composing preview, and the blinking insertion cursor.
- Stores no canonical document state; `MainActivity.columnData` remains the source of truth.
- `cellRect(index)` and `cellIndexAtTouch(x, y)` are the geometry APIs used by focus, selection handles, keyboard scrolling, and touch placement.
- Canvas width is always `MAX_COLUMNS * currentCellSize`, so horizontal scroll math and the custom scroll indicator stay stable.

### `ghostInput` in `MainActivity.kt`

- A hidden 1x1 `EditText` attached directly to `rootFrame`.
- Holds `GHOST_SENTINEL` so backspace can be detected when the sentinel is deleted.
- `setupGhostInput()` owns hardware/backspace handling, Enter, IME composing preview, paste/multi-char commits, and single-char typing.
- `focusCell(...)` requests focus on `ghostInput`, restarts IME when needed, and draws the real cursor on `poemCanvas`.

## Current Helper Structure

- `withRestoring { ... }`: sets `isRestoring` safely around programmatic mutations.
- `persistCurrentState()`: saves both current session JSON and SharedPreferences.
- `advanceToNextCell(index, total)`: moves focus to `index + 1`, calls `poemCanvas.setCursor(nextIdx)`, applies upper-view top-scroll logic, scrolls column into view.
- `postRefreshFocusColumn(targetIndex, col)`: posts `refreshGrid()`, `focusCell(...)`, and `scrollToColumn(...)`.
- `scrollToTopIfCursorInUpperView(index)`: if `poemCanvas.cellRect(index).top` is in the upper 35% of `mainScrollView`, posts `mainScrollView.scrollTo(0, 0)`. This is the universal fix for cursor placement after word wrap, Enter/newline, paste/IME commits, tap focus, and backspace.
- `refreshModeChips()`: only place that recolors writing mode chips.
- `columnDataToJson()`, `columnBreaksToJson()`, `loadColumnDataFromJson(...)`, `loadColumnBreaksFromJson(...)`: thin wrappers delegating to `SessionManager`.
- `setColumnChar(col, row, ch)`, `reflowColumnData(newNumRows)`, `insertCharsAt(...)`: thin wrappers delegating to `GridLogicHelper`.
- `placeFrontierMarker()`, `fillGapsForSequentialMode()`, `findSequentialTapTarget(index)`: thin wrappers delegating to `GridLogicHelper` — all pure data logic lives there.
- `insertCharsAt(insertCol, insertRow, newChars)`: flat-slice insert-shift; captures content from `(insertCol, insertRow)` forward, writes `newChars`, re-appends displaced content. Never crosses a `columnBreaks` boundary. Trailing **blank** cells (empty or space) are trimmed from the captured slice so island overflow never pushes spaces forward.
- `performInsert(newChars)`: shared insert core; always inserts at `focusedCellIndex`, calls `insertCharsAt`, returns `lastWrittenIdx + 1` (the new cursor position).
- `deletePreviousSequentialCell(cellCol, cellRow, index)`: SEQUENTIAL backspace — removes the char at `(cellCol, cellRow-1)` (or last occupied in prev column when `cellRow==0`), reflows, refocuses.
- `removeColumnBreak(cellCol)`: reverse of `handleEnter`; clears the `LINE_END_MARKER` from the preceding column, removes the break, reflows the two columns back together, and lands the cursor at the last occupied position in the preceding column.
- `makeRoomBeforeParagraphBreakForLineEndInsert(cellCol, cellRow, insertedLength)`: called before `performInsert` when typing onto a `LINE_END_MARKER` cell that sits at a paragraph boundary. Inserts empty columns between the current paragraph and the next so the new chars have room without overflowing into the following paragraph.
- `showPopupSeekbar(anchor, max, initial, format, onChange)`: floating `PopupWindow` seekbar anchored above a chip; used for font size (字號) and word gap (字距).

## Reflow

`reflowColumnData(newNumRows)` redistributes content when column height changes. It is skipped in SCATTER mode.

Reflow stream rules:

- `null` means explicit column break.
- `""` placeholders are preserved inside non-empty ranges.
- trailing empty cells are excluded.
- explicit break columns without content must still be represented so empty breaks survive.

## Focus And Cursor

`focusCell(index, showKeyboard)` is central focus management.

- `focusedCellIndex` IS the insertion point; cursor (`poemCanvas.setCursor(index)`) is always drawn at the top of that cell.
- Calls `scrollToTopIfCursorInUpperView(index)` after placing the canvas cursor. This is intentionally based on the cursor's vertical position, not the input path, so word wrap and Enter share the same behavior.
- Tapping the top half of an occupied cell calls `focusCell(index)`; tapping the bottom half calls `focusCell(index + 1)`, letting the user insert before or after a character.
- Requests focus on `ghostInput`.
- Shows/restarts IME when requested.
- Schedules keyboard translation.

Touch listeners consume all touch events. Native cursor placement should not decide insertion location.

## Input Modes

### SEQUENTIAL

Label in UI: 連續輸入.

- **Writing-frontier marker**: `FRONTIER_MARKER` = `"​"` (zero-width space) and `LINE_END_MARKER` = `"↵"` are `const val` in `GridLogicHelper`; `MainActivity` and `PoemCanvasView` both reference them there. `FRONTIER_MARKER` sits in the cell immediately after the last real-content cell **in the last paragraph**. `LINE_END_MARKER` is placed at the start of the first empty column after the last real-content cell **in every non-last paragraph** (i.e. row 0 of the column immediately following content). Both markers keep cells non-empty so taps land there directly. `placeFrontierMarker()` (logic in `GridLogicHelper`, thin wrapper in `MainActivity`) manages both: it is called from `refreshGrid`, `advanceToNextCell`, `rebuildGrid`, and `restoreHistoryState`. It is **idempotent** and self-correcting:
  1. Locates the last real-content cell per paragraph (anything that isn't empty, FRONTIER_MARKER, or LINE_END_MARKER).
  2. **Demotes** any stale marker sitting before the new frontier position to `" "` (gap-filler).
  3. Places the appropriate marker (`FRONTIER_MARKER` for the last para, `LINE_END_MARKER` for others) at the frontier, or no-ops if one is already there.
  4. Empty non-last paragraphs get `LINE_END_MARKER` at row 0 of their start column to show the blank line visually.
- **Empty-cell tap redirect** (`findSequentialTapTarget` logic in `GridLogicHelper`, thin wrapper in `MainActivity`, invoked from `setupCanvasTouchListener`):
  1. If the tapped column contains a `FRONTIER_MARKER` or `LINE_END_MARKER`, the cursor jumps to that marker.
  2. Otherwise the search walks **rightward** in the RTL layout — decreasing column index — within the same paragraph (bounded by `columnBreaks`), and jumps to the first column holding a marker.
  3. If the paragraph has no content at all (empty document or empty fresh paragraph), the cursor lands at `paraStart` row 0 so the user begins at the natural origin.
  4. Otherwise no redirect — the tap lands where the user clicked.
- Occupied typing uses insert-shift.
- Occupied punctuation uses insert-shift.
- DEL removes previous content and calls `reflowColumnData(numRows)`.
- Enter places `LINE_END_MARKER` (↵) at the cursor cell, extracts the tail, inserts a new structural column on the left for the tail, and shifts subsequent paragraphs right.
- Switching SCATTER -> SEQUENTIAL calls `fillGapsForSequentialMode()` (logic in `GridLogicHelper`, thin wrapper in `MainActivity`).

### SCATTER

Label in UI: 灑花輸入.

- **Canvas is fixed-width** — `PoemCanvasView` always renders the `MAX_COLUMNS` skeleton. SCATTER does not structurally shift text through SEQUENTIAL insert paths; paste/multi-char writes are capped by the existing data bounds for the mode.
- Tap lands exactly where tapped.
- Occupied **space** cell or empty cell: typing overwrites current cell and advances one cell.
- Occupied **non-blank** cell (island): typing uses insert-shift, same as SEQUENTIAL.
- DEL on a **non-blank** cell (island): backspace-delete with reflow, same as SEQUENTIAL.
- DEL on an empty or space cell: removes at current row and pads with `""` to preserve row alignment.
- Punctuation on a **non-blank** cell (island): insert-shift, same as SEQUENTIAL.
- Punctuation on an empty or space cell: writes directly and advances.
- Enter writes `LINE_END_MARKER` (↵) at the cursor cell and adds a `columnBreaks` entry for the next column without structural shift.
- Font/gap changes do not reflow content.

### SCATTER Islands

An **island** is any maximal run of consecutive non-blank (`isNotBlank()`) characters in column-major order. Space (`" "`) explicitly breaks islands and is not part of any island.

When an operation lands on a cell whose content `isNotBlank()`, SCATTER treats it identically to SEQUENTIAL:
- Typing → `performInsert` (insert-shift)
- DEL → `deletePreviousSequentialCell` (backspace-reflow)
- Punctuation → `performInsert`

When island content overflows into the following cells during insertion, trailing blank cells (empty or space) are overridden rather than pushed — `insertCharsAt` trims trailing blanks (`isBlank()`) from the captured slice before writing displaced content.

## Enter / Newline

All Enter paths call `handleEnter()`:

- hardware `KEYCODE_ENTER`
- soft IME editor action
- single committed `\n` caught by `TextWatcher`

**SEQUENTIAL**: extracts the tail (cursor row onwards), places `LINE_END_MARKER` (↵) at the cursor cell, does a structural column insert (`columnData.add(nextCol, tail)`) so following paragraphs shift right, and jumps to `nextCol * numRows`.

**SCATTER**: writes `LINE_END_MARKER` in place at the cursor cell and adds a `columnBreaks` entry for `nextCol` without structural shift.

Backspace on a `LINE_END_MARKER` cell calls `removeColumnBreak` to reverse the split.

## Character Input

`TextWatcher.afterTextChanged` flow:

- ignored when `isRestoring`, `isPreviewing`, or local `suppress` is true
- composing spans show grey preview in following empty cells
- empty raw text: if originalChar `isNotBlank()` (island) OR SEQUENTIAL → `deletePreviousSequentialCell`; otherwise SCATTER clear-and-back
- SCATTER + occupied multi-char + `isNotBlank()` (island) → insert-shift via `performInsert`
- SCATTER + occupied multi-char + blank → overwrite and advance (SCATTER)
- single char + SCATTER + originalChar `isNotBlank()` (island) → `performInsert`
- single char otherwise → write current cell and advance
- occupied SEQUENTIAL multi-char commit calls insert-shift via `performInsert(...)`
- paste/multi-line input distributes manually and records `columnBreaks`

`performInsert(...)` + `insertCharsAt(...)` handle insert-shift and do not cross explicit break boundaries.

## Punctuation

`buildPunctRow(dp)` is shared by `punctToolbar` (above keyboard when IME is open) and the `punctBar` inside the toolbar `FrameLayout` (shown when 標點 is tapped).

`insertPunct(punct)` behavior:

- empty cell OR (SCATTER + blank cell): write punctuation directly, update view under `withRestoring`, advance one cell
- non-blank cell (island) OR SEQUENTIAL occupied: call `performInsert(...)`, then `postRefreshFocusColumn(...)`

## Text Selection

Double-tap a cell → `enterSelectionMode(index)`: hides IME, sets `isSelecting = true`, places `selectionStart = selectionEnd = index`, highlights the cell, positions start and end handles.

**Handles** (`startHandle`, `endHandle`): blue oval views in `rootFrame`. Positioned by `positionHandleAt(handle, index, atTop)` (uses `getGlobalVisibleRect`) and `fastPositionHandleByIndex` (uses col-0 anchor, immune to scrollX drift — used during drag).

**Drag** is intercepted in `dispatchTouchEvent`:
- `ACTION_DOWN`: hit-test both handles within 36 dp radius; sets `activeDragHandle` (true=start, false=end); caches `cachedCellSize`, `cachedRootLoc`.
- `ACTION_MOVE`: calls `fastCellIndexAtPoint(rawX, rawY)` → schedules one `postOnAnimation` frame per vsync to `updateHighlightDiff` + reposition both handles via `fastPositionHandleByIndex`.
- `ACTION_UP`: if no drag occurred, shows `handlePasteView` bubble (only when `selectionOptionsView` is hidden).

`fastCellIndexAtPoint(rawX, rawY)`: uses col-0 cell's screen position as ruler; clamps result to last occupied column so handles never jump into empty space.

**Selection options bar** (`selectionOptionsView`): vertical `LinearLayout` positioned to the left of the selection's top-left cell. Contains 複製, 剪下, 貼上, 選取整行, 選取整段, 全選. Falls back to right side if no room on left.

`clearSelection()`: resets all selection state, hides handles, options bar, and paste bubble; clears highlight by diff-painting only previously highlighted cells.

`updateHighlightDiff(newFrom, newTo)`: only repaints cells at the boundary between old and new selection ranges to avoid full redraws during drag.

## Composing Preview

During CJK composition:

- `showComposingPreview(text, startIndex)` renders chars after the first composing char into following empty cells in grey.
- `clearComposingPreview()` clears preview cells and restores `gridTextColor`.
- `isPreviewing` suppresses watcher side effects during preview updates.

## Backgrounds

Solid color (底色 section) calls `applyBackground(color)`:
- sets `bgColor`; calls `rootFrame.setBackgroundColor(color)`; persists.
- Does **not** remove inserted images.

Inserted image (插入 section) via `applyInsertedImage(uri)`:
- supports up to `MAX_INSERTED_IMAGES = 5`.
- copies each image to app-owned storage (`filesDir/backgrounds/{sessionId}_{timestamp}.bg`).
- appends to `insertedImages: MutableList<InsertedImageState>`, then marks the new entry active.
- `syncActiveImageFromList()` re-renders all inserted images as overlay layers (not replacement).
- `refreshInsertedImagePanel()` renders small avatars; tap avatar selects active image, tap small `×` deletes that image.
- persists via session JSON + SharedPreferences.

Image transforms:
- `InsertedImageState` stores per-image matrix values (`FloatArray(9)`).
- active image matrix is mirrored through `bgImageMatrix`/`bgImageUri` for gesture + backward compatibility.
- matrix values are restored on session load and cold start.


## Sessions

SharedPreferences name: `poem_editor_state`.

Session files: `filesDir/poems/{id}.json`.

Session JSON includes:

```json
{
  "id": "uuid",
  "name": "Document",
  "lastAccessed": 1234567890000,
  "columnData": [["..."]],
  "columnBreaks": [1, 3],
  "fontIndex": 0,
  "fontSizeSp": 24.0,
  "wordGapDp": 0.0,
  "gridTextColor": -16777216,
  "bgColor": -1,
  "bgImageUri": "",
  "bgImageMatrix": [1, 0, 0, 0, 1, 0, 0, 0, 1],
  "insertedImages": [
    { "uri": "file://...", "matrix": [1, 0, 0, 0, 1, 0, 0, 0, 1] }
  ],
  "activeImageIndex": 0,
  "inputMode": "SEQUENTIAL"
}
```

Compatibility:
- `bgImageUri`/`bgImageMatrix` are retained as legacy-compatible fields.
- New sessions primarily use `insertedImages` + `activeImageIndex`.
- Load path migrates legacy single-image sessions into the new list model when needed.


Important functions:

- `saveSession()`: writes current session content + settings to JSON via `editorViewModel.saveSession(...)`.
- `loadSessionFile(id)`: saves current, loads target, applies settings, rebuilds.
- `ensureDefaultSession()`: creates initial session if none exist (cold start).
- `updateToolbarSessionName()`: syncs `docFileNameRef` text to `currentSessionName`.
- `SessionListActivity`: full searchable/filterable session list; 新增 button (creates session with name dialog), inline rename (✏) and delete (🗑); returns `renamed_current_name` extra when the active session is renamed. Calls `SessionManager` directly — does not route through `EditorViewModel`/`SessionRepository`.

## Toolbar

`toolbar` is a `FrameLayout` (54dp tall) with two alternating children:

- `mainBar` (default VISIBLE): `buildCategoryCell("工具")` | `divider` | `↩` | `↪` | `divider` | `buildCategoryCell("。，、")` | `divider` | `截`
- `punctBar` (default GONE): `←` back button (54dp wide) | `divider` | `HScrollView(buildPunctRow(dp))`

Tapping 工具 calls `switchToTools()` / `switchToKeyboard()`. Tapping 標點 swaps bar visibility. No persistent state flag for the bar swap — purely view visibility toggling via captured `mainBarRef`/`punctBarRef` locals.

`buildCategoryCell(label, toolbarH, onClick)` creates a weight-1f `LinearLayout` with centered label text. `toolsCell` holds a reference to the 工具 cell for background highlight in `switchToTools/switchToKeyboard`.

## Tools Panel

`allToolsPanel` is a `LinearLayout` overlaid on `rootFrame` at `Gravity.BOTTOM`. Height is set to `lastKeyboardHeight` when shown (fallback 280dp). It is GONE by default and toggled by `switchToTools/switchToKeyboard`. It contains a collapse `▾` button at the top, then a `NestedScrollView` wrapping the section content.

Sections (top to bottom), each separated by a `subDivider`:

- **字型** (no label): `buildFontRow(dp)` — font spinner + 字號 chip + 字距 chip, all inline with `rowDivider`s
- Text color swatch row (horizontal scroll, no label)
- **底色** inline row: `[底色 label] [HScrollView(color swatches)]` — `buildBgColorHeaderRow(dp)`
- **插入** inline row: `[插入 label] [spacer] [選圖 chip] [insertImageContainer]` — `buildInsertPanel(dp)`
- **輸入模式** inline row: `[輸入模式 label] [spacer] [灑花輸入 chip] [連續輸入 chip]` — `buildModeHeaderRow(dp)`; `modeChipContainer` points to the chips LinearLayout
- **檔案** inline row: `[檔案 label] [current filename flex] [所有文檔 chip]` — `buildFileHeaderRow(dp)`; `docFileNameRef` points to the filename TextView

Font size and word gap use `showPopupSeekbar(anchor, max, initial, format, onChange)` — a `PopupWindow` with a `SeekBar` that floats above the tapped chip. Both seekbars map seekbar position → discrete value via `AppConfig.FONT_SIZE_LIST` / `AppConfig.WORD_GAP_LIST`. The display label (chip text) is a curated string from the list, not the raw value — e.g. `WORD_GAP_LIST = [(3f,"0"), (5f,"2"), ...]` keeps the user-visible label tidy while the underlying dp value drives layout math.

## Undo / Redo

`EditorHistoryState` snapshots text + full session config:
- `columnData`, `columnBreaks`
- `fontIndex`, `fontSizeSp`, `wordGapDp`
- `gridTextColor`, `bgColor`, `inputMode`
- `insertedImages`, `activeImageIndex`

- `undoStack / redoStack`: `ArrayDeque<EditorHistoryState>`, max 50 entries.
- `pushHistory()`: called at direct user-action mutation points (text edit paths, punctuation, paste/cut, font/font-size/gap/text-color/bg-color/mode changes, image add/remove/select).
- `performUndo/performRedo`: swap snapshots, restore with `restoreHistoryState(...)`, then refresh and update button enabled state.
- `restoreHistoryState(...)`: restores both content and settings via `applySettings(...)`, including images and active index.
- Session load / new session clears both stacks.
- `updateUndoRedoButtons()`: sets ↩/↪ text color to `text_dark` (enabled) or `text_hint` (disabled).


## Screenshot

Owned by `ScreenshotController` (initialized `by lazy` in `MainActivity`). `MainActivity.takeScreenshot()` just delegates to `screenshotController.takeScreenshot()`.

Flow:
1. Hides transient views (handles, selection options, paste bubble); hides `allToolsPanel` if open; stops cursor blink on `PoemCanvasView`.
2. If the soft keyboard is open, dismisses it and waits for the next `OnGlobalLayoutListener` callback before continuing (so the layout settles).
3. Shows `ScreenshotCropView` — a full-screen overlay with a 4-corner draggable crop region. The initial region is pre-set to the poem grid bounds (top/bottom computed from `gridContainer` location + `numRows * cellSize`). The area outside the crop rect is dimmed; the selected region shows live content through.
4. User drags corner handles to adjust the crop, then taps 確認 or 取消.
5. On confirm: hides `ScreenshotCropView`, sets `poemCanvas.hideLineEndMarkers = true`, calls `captureView(rootFrame, cropL, cropT, cropW, cropH)` — translates the Canvas so only the cropped rect is drawn, then resets `hideLineEndMarkers`. Saves result via `saveToGallery`.
6. Restores `bottomPanel`, cursor blink, `allToolsPanel` (if was open), and selection handles via `Callbacks.onScreenshotRestored()`.

`captureView` and `saveToGallery` are private methods of `ScreenshotController`:
- API 29+: `MediaStore.Images` with `RELATIVE_PATH = Pictures/Screenshots` (no permission needed).
- API 24–28: writes to `Environment.DIRECTORY_PICTURES`, uses `MediaScannerConnection`. Requires `WRITE_EXTERNAL_STORAGE` (declared in manifest with `maxSdkVersion="28"`).

## Inserted Image (Overlay)

Background color (`bgColor`) and inserted images are **independent layers**:
- `rootFrame.setBackgroundColor(bgColor)` is always the solid base.
- Inserted images are rendered as multiple `ImageView` overlays (`bgImageViews`) behind the editor UI.
- `applyBackground(color)` only changes `bgColor`; it does not remove inserted images.

Image lifecycle:
- `onInsertImageRequested()` → `imagePickerLauncher` → `applyInsertedImage(uri)` (max 5 images/session).
- Each image is copied to app-owned storage (`filesDir/backgrounds/{sessionId}_{timestamp}.bg`) and added to `insertedImages`.
- `syncActiveImageFromList()` re-renders all inserted images so previously inserted images remain visible.
- `refreshInsertedImagePanel()` populates `insertImageContainer` with small avatars + a small `×` delete control beside each avatar.
  - Tap avatar: select active image (used for gesture transform target).
  - Tap `×`: remove only that image.

Image transform:
- `InsertedImageState.matrix` stores per-image transform values (9 floats).
- Active image transform is mirrored via `bgImageMatrix`/`bgImageUri` for gesture handling and backward compatibility.
- Two-finger pinch/pan in `dispatchTouchEvent` now hit-tests the touch midpoint and retargets to the touched image (topmost hit) before applying transform.
- Matrix values persist in both session JSON and SharedPreferences.

## Invariants

- Do not use XML layout changes for active editor behavior.
- Do not rebuild grid on keyboard open/close.
- Use `withRestoring` for programmatic text/model mutations.
- Preserve mode differences. Most regressions come from accidentally sending SCATTER through SEQUENTIAL insert-shift paths — use the island check (`isNotBlank()`) to gate correctly.
- Keep custom background persistence app-owned, not dependent on external URI permission.
- `stableMaxHeight` must never be derived while the IME is open. The `OnGlobalLayoutListener` guards this with an `!imeVisible` check.
- The toolbar `FrameLayout` height and `allToolsPanel` height must match `lastKeyboardHeight` so the grid's paddingBottom transition is seamless.
- `numColumns = MAX_COLUMNS = 50` always; never recalculate from data size. The grid skeleton is always full width.
- `PoemCanvasView` is the only visible text/cursor/selection renderer; do not reintroduce per-cell `EditText` rendering.
- Keep `ghostInput` invisible and out of document state. It is only for IME/backspace/composition capture.
- Cursor-driven vertical reset belongs in `scrollToTopIfCursorInUpperView`, not in individual typing/Enter/paste branches.
- `insertCharsAt` trims only **trailing** blank cells (`isBlank()`) from the captured slice before writing displaced content. Internal blanks (spaces between characters) are preserved in position — do not add a "blank budget" or any mechanism that absorbs internal blanks, as that would silently drop gap-fill spaces in SEQUENTIAL mode.
- ViewFactory interface method names must not clash with MainActivity property getter names (use `provide*` prefix for catalogue-style accessors).
- `applyBackground(color)` sets only `bgColor` and `rootFrame` background — it never removes inserted images.
- `pushHistory()` should be called at direct user-action mutation entry points to avoid duplicate history frames.
- Keep `insertedImages` as the primary model; `bgImageUri`/`bgImageMatrix` are now mainly the runtime cache for the currently active image (gesture target + quick persistence path), not the main multi-image storage model.
- Style/setting changes (font, size, word-gap, colors) happen only while the tools panel is open and IME is hidden, so `rebuildGrid()` is acceptable there. Reserve `refreshGrid()` (no view destruction) for content edits made with the IME open.
- SCATTER must not be routed through SEQUENTIAL structural insert-shift behavior unless the local island rule explicitly allows it.
- `FRONTIER_MARKER` and `LINE_END_MARKER` are defined once in `GridLogicHelper` as `const val`. Do not redefine them in `MainActivity` or `PoemCanvasView` — reference `GridLogicHelper.FRONTIER_MARKER` / `GridLogicHelper.LINE_END_MARKER` directly (or via the private `get()` aliases in `MainActivity`).
- Pure data logic on `columnData`/`columnBreaks` (marker placement, gap-fill, tap targeting) belongs in `GridLogicHelper`, not in `MainActivity`. `MainActivity` keeps thin wrappers. Scrolling coordination (`scrollToColumn`, `scrollToTopIfCursorInUpperView`) is editor-level and stays in `MainActivity`; it does not belong in `ViewFactory` or `PoemCanvasView`.

## Build

```bash
./gradlew.bat assembleDebug
./gradlew.bat installDebug
./gradlew.bat test
./gradlew.bat connectedAndroidTest
```
