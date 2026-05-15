# poemeditor

Android app for composing text in vertical, right-to-left column order. Characters flow top-to-bottom inside a column; columns progress right-to-left.

All active UI is programmatic in `MainActivity.kt`.

## Project Layout

```text
app/src/main/
|-- assets/fonts/LXGWWenKai-Regular.ttf
|-- java/com/poemeditor/
|   |-- MainActivity.kt                  # activity coordinator: TextWatcher, selection overlay, image gestures, keyboard insets
|   |-- ViewFactory.kt                   # programmatic UI factory; Callbacks bridge to MainActivity
|   |-- CellInputController.kt           # extracted cell touch + hardware key listeners; owns lastTapIndex/lastTapTime
|   |-- GridEditorController.kt          # extracted grid column/lazy build controller
|   |-- GridLogicHelper.kt               # pure data logic: setColumnChar, reflowColumnData, insertCharsAt
|   |-- EditorViewModel.kt               # undo/redo stacks + thin save/load/ensureDefault wrappers over SessionRepository
|   |-- EditorStateModels.kt             # InsertedImageState + EditorHistoryState
|   |-- SessionRepository.kt             # one-method-each delegate over SessionManager (save/load/ensureDefault)
|   |-- SessionManager.kt                # JSON conversion + file I/O helpers; ScreenshotHelper companion
|   |-- ScreenshotHelper.kt              # captureView + saveToGallery (MediaStore / legacy path)
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
|   |           `-- GridLayout RTL, numRows x MAX_COLUMNS (skeleton always MAX_COLUMNS wide)
|   |               `-- EditText cells  ← only built columns have cells; unbuilt cols have no views
|   `-- LinearLayout bottomPanel        ← built by ViewFactory.buildBottomPanel()
|       |-- divider
|       |-- HorizontalScrollView punctToolbar    ← visible only while IME is open
|       `-- FrameLayout toolbar, 54dp, white
|           |-- LinearLayout mainBar             ← default visible: 工具 | divider | 標點
|           `-- LinearLayout punctBar            ← shown when 標點 tapped: ← | divider | HScrollView(punct)
|-- NestedScrollView allToolsPanel               ← Gravity.BOTTOM overlay, height = lastKeyboardHeight
|   `-- LinearLayout content (vertical)
|       |-- buildFontRow (no label): font spinner + 字號 chip + 字距 chip, all inline
|       |-- text color swatch row (no label)
|       |-- 底色 inline row: label + HScrollView(bg color swatches)
|       |-- 插入 inline row: 選圖 chip + avatar list (tap avatar = select active, × = delete)
|       |-- 輸入模式 inline row: 灑花輸入 / 連續輸入 chips
|       `-- 檔案 inline row: filename + 所有文檔 chip
|-- View startHandle                             ← blue oval, positioned by positionHandleAt()
|-- View endHandle                               ← blue oval, positioned by positionHandleAt()
|-- LinearLayout selectionOptionsView            ← 複製/剪下/貼上/選取整行/選取整段/全選, shown left of selection
`-- TextView handlePasteView                     ← mini 貼上 bubble shown on handle tap (no drag)
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
- `EditText` content is a view cache, not canonical state.
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

`rebuildGrid()` computes this and rebuilds all cells. It is for settings/session changes, not keyboard open/close. Style changes (font, size, gap, color) only fire while the tools panel is open, where IME is closed by design — so the rebuild's IME interruption is not a concern.

`refreshGrid()` diffs current `EditText` views against `columnData` and updates in place. It only iterates built columns (`editTextFields.size / numRows`). Prefer it for content changes so the IME does not flicker.

## Lazy Grid Loading

The grid uses **on-demand column construction** to keep startup fast and the UI thread free during typing.

### Initial build (`buildInitialColumns`)

Called once at the end of `rebuildGrid`. Synchronously builds exactly:

```text
max(visibleCols, lastDataCol + 1)
```

where `visibleCols = mainScrollView.width / cellSize + 1`. This guarantees col 0 (top-right) and all content columns are present on the first frame. No background loop is started.

### On-demand growth

New columns are created only when actually needed:

- **Typing reaches the last built column**: `ensureBufferColumn()` → `ensureColumnBuilt(columnData.size)` adds exactly one column.
- **Scrolling reveals unbuilt columns**: `onScrollChangeListener` computes `highestVisible = (MAX_COLUMNS * cs - scrollX) / cs` and calls `ensureColumnBuilt(highestVisible + 1)` if beyond last built.
- **Focus / handle drag into unbuilt column**: `focusCell(index)` calls `ensureColumnBuilt(index / numRows)` before accessing `editTextFields`.

### Key fields

- `builtColumns: HashSet<Int>` — which column indices have cells in the grid.
- Grid lazy-build routines are now hosted in `GridEditorController`; `MainActivity` delegates `buildInitialColumns()` / `ensureColumnBuilt(...)` to the controller.

### `buildColumn(col, grid, fontPx, cellSize)`

Appends `numRows` EditText cells for `col` to `editTextFields` and `grid`. Sets `isRestoring = true` during construction so TextWatcher never fires. Must always be called in ascending column order because `editTextFields` is a contiguous list (`index = col * numRows + row`).

### `ensureColumnBuilt(col)`

Calls `buildColumn` for every unbuilt column from `builtColumns.max + 1` up to `col` inclusive. Synchronous; safe to call on the UI thread at any time.

## Current Helper Structure

- `withRestoring { ... }`: sets `isRestoring` safely around programmatic mutations.
- `persistCurrentState()`: saves both current session JSON and SharedPreferences.
- `advanceToNextCell(index, total)`: moves focus to `index + 1`, sets `cursorBefore = true`, scrolls column into view.
- `postRefreshFocusColumn(targetIndex, col)`: posts `refreshGrid()`, `focusCell(...)`, and `scrollToColumn(...)`.
- `refreshModeChips()`: only place that recolors writing mode chips.
- `columnDataToJson()`, `columnBreaksToJson()`, `loadColumnDataFromJson(...)`, `loadColumnBreaksFromJson(...)`: thin wrappers delegating to `SessionManager`.
- `setColumnChar(col, row, ch)`, `reflowColumnData(newNumRows)`, `insertCharsAt(...)`: thin wrappers delegating to `GridLogicHelper`.
- `insertCharsAt(insertCol, insertRow, newChars)`: flat-slice insert-shift; captures content from `(insertCol, insertRow)` forward, writes `newChars`, re-appends displaced content. Never crosses a `columnBreaks` boundary. Trailing **blank** cells (empty or space) are trimmed from the captured slice so island overflow never pushes spaces forward.
- `performInsert(index, newChars)`: shared insert core; computes the `(iCol, iRow)` insertion point from `cursorBefore`, calls `insertCharsAt`, returns the focus target index after the inserted chars.
- `deletePreviousSequentialCell(cellCol, cellRow, index)`: SEQUENTIAL backspace — removes the char at `(cellCol, cellRow-1)` (or last occupied in prev column when `cellRow==0`), reflows, refocuses.
- `removeColumnBreak(cellCol)`: reverse of `insertColumnBreak`; removes the break marker at `cellCol`, reflows the two columns back together, and lands the cursor at the last occupied position in the preceding column.
- `fillGapsForSequentialMode()`: when switching SCATTER→SEQUENTIAL, lays out each paragraph per-column:
  - **Columns with content**: rows `0..lastOccupiedRow` are filled with `" "` (half-width gap-fill space) wherever empty; one `FRONTIER_MARKER` is placed at `lastOccupiedRow + 1` (if it fits in the column). Cells beyond stay empty.
  - **Empty columns sandwiched between content columns** (i.e. in `firstContentCol+1 .. lastContentCol-1`): a single `FRONTIER_MARKER` at row 0; the rest of the column stays empty.
  - **Empty columns outside the content span** (before the first content column or after the last): left entirely empty.
  - Each paragraph (delimited by `columnBreaks`) is processed independently. `FRONTIER_MARKER` (not `" "`) is used for line-ending cells, so `placeFrontierMarker` can later distinguish them from gap-fill spaces and clean up stale ones.
- `showPopupSeekbar(anchor, max, initial, format, onChange)`: floating `PopupWindow` seekbar anchored above a chip; used for font size (字號) and word gap (字距).

## Reflow

`reflowColumnData(newNumRows)` redistributes content when column height changes. It is skipped in SCATTER mode.

Reflow stream rules:

- `null` means explicit column break.
- `""` placeholders are preserved inside non-empty ranges.
- trailing empty cells are excluded.
- explicit break columns without content must still be represented so empty breaks survive.

## Focus And Cursor

`focusCell(index, cursorBefore, showKeyboard)` is central focus management.

- Calls `ensureColumnBuilt(index / numRows)` first — guarantees the column's cells exist before accessing `editTextFields[index]`.
- Stores logical insertion side in `cursorBefore`.
- Requests focus and sets selection to 0.
- Shows/restarts IME when requested.
- Schedules keyboard translation.

Touch listeners consume all touch events. Native cursor placement should not decide insertion location.

## Input Modes

### SEQUENTIAL

Label in UI: 連續輸入.

- **Writing-frontier marker**: `FRONTIER_MARKER` = `"​"` (zero-width space) sits in the cell immediately after the paragraph's last real-content cell. The marker keeps that cell non-empty so the user can tap it directly (the empty-tap redirect at `CellInputController.kt:57` is skipped). It's a distinct character from `" "` (gap-fill space) so stale markers can be detected and cleaned up safely without touching real spaces. Typing onto the marker goes through the existing insert-shift path — the marker is trimmed (it's `isBlank()`), the new char takes its place, and `placeFrontierMarker()` re-places a fresh marker one cell forward. `placeFrontierMarker()` is called from `refreshGrid` (start-of — covers every post-mutation refresh including insert-shift, paste, delete, column break), `advanceToNextCell` (covers single-char writes that bypass `refreshGrid`), `rebuildGrid` (end-of), and `restoreHistoryState`. It is **idempotent** and self-correcting:
  1. Locates the last cell whose content is neither empty nor a marker (the "real-content frontier").
  2. **Demotes** any marker positioned before that frontier to `" "` (a gap-filler) — this handles the case where the user tapped past a marker via the redirect and typed elsewhere, leaving the old marker orphaned.
  3. Places a fresh marker at the frontier, or no-ops if one is already there.
- **Empty-cell tap redirect** (`findSequentialTapTarget` in `MainActivity`, invoked from `CellInputController.attachTouchListener`):
  1. If the tapped column already contains a `FRONTIER_MARKER`, the cursor jumps to that marker.
  2. Otherwise the search walks **rightward** in the RTL layout — decreasing column index — within the same paragraph (bounded by `columnBreaks`), and jumps to the first `FRONTIER_MARKER` it finds.
  3. If the paragraph has no content at all (empty document or empty fresh paragraph), the cursor lands at `paraStart` row 0 so the user begins at the natural origin.
  4. Otherwise no redirect — the tap lands where the user clicked.
- Occupied typing uses insert-shift.
- Occupied punctuation uses insert-shift.
- DEL removes previous content and calls `reflowColumnData(numRows)`.
- Enter splits before the focused row, inserts a new column on the left, and shifts existing columns left/preserves them.
- Switching SCATTER -> SEQUENTIAL calls `fillGapsForSequentialMode()`.

### SCATTER

Label in UI: 灑花輸入.

- **Canvas is frozen** — SCATTER never grows the built-column range. `focusCell`, `ensureBufferColumn`, the `hScroll` scroll listener, and the paste-multi loop all gate their `ensureColumnBuilt` / column-extension calls on `inputMode != InputMode.SCATTER`. Paste content beyond the last built column is dropped. Column growth (and lazy build on scroll) only happens in SEQUENTIAL mode.
- Tap lands exactly where tapped.
- Occupied **space** cell or empty cell: typing overwrites current cell and advances one cell.
- Occupied **non-blank** cell (island): typing uses insert-shift, same as SEQUENTIAL.
- DEL on a **non-blank** cell (island): backspace-delete with reflow, same as SEQUENTIAL.
- DEL on an empty or space cell: removes at current row and pads with `""` to preserve row alignment.
- Punctuation on a **non-blank** cell (island): insert-shift, same as SEQUENTIAL.
- Punctuation on an empty or space cell: writes directly and advances.
- Enter splits before the focused row, moves current row and following rows into the left column at row 0, and overwrites that target column instead of inserting a new one.
- Font/gap changes do not reflow content.

### SCATTER Islands

An **island** is any maximal run of consecutive non-blank (`isNotBlank()`) characters in column-major order. Space (`" "`) explicitly breaks islands and is not part of any island.

When an operation lands on a cell whose content `isNotBlank()`, SCATTER treats it identically to SEQUENTIAL:
- Typing → `performInsert` (insert-shift)
- DEL → `deletePreviousSequentialCell` (backspace-reflow)
- Punctuation → `performInsert`

When island content overflows into the following cells during insertion, trailing blank cells (empty or space) are overridden rather than pushed — `insertCharsAt` trims trailing blanks (`isBlank()`) from the captured slice before writing displaced content.

## Enter / Newline

All Enter paths call `insertColumnBreak(cellCol, cursorRow)`:

- hardware `KEYCODE_ENTER`
- soft IME editor action
- single committed `\n` caught by `TextWatcher`

Cells keep selection at 0, so Enter splits before `cursorRow`. The focused cell belongs to the moved tail.

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

`allToolsPanel` is a `NestedScrollView` overlaid on `rootFrame` at `Gravity.BOTTOM`. Height is set to `lastKeyboardHeight` when shown (fallback 280dp). It is GONE by default and toggled by `switchToTools/switchToKeyboard`.

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

`takeScreenshot()`:
1. Hides handles, selection overlay, paste bubble; hides `allToolsPanel` temporarily; sets `isCursorVisible = false` on the focused cell.
2. Creates `Bitmap(rootFrame.width, mainScrollView.height)` — the canvas height naturally clips the toolbar off the bottom.
3. Calls `rootFrame.draw(canvas)`.
4. Restores visibility; saves via `saveBitmapToGallery`.
- API 29+: `MediaStore.Images` with `RELATIVE_PATH = Pictures/PoemEditor` (no permission needed).
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
- Keep `EditText` ids/tags unique across rebuilds.
- Keep custom background persistence app-owned, not dependent on external URI permission.
- `stableMaxHeight` must never be derived while the IME is open. The `OnGlobalLayoutListener` guards this with an `!imeVisible` check.
- The toolbar `FrameLayout` height and `allToolsPanel` height must match `lastKeyboardHeight` so the grid's paddingBottom transition is seamless.
- `numColumns = MAX_COLUMNS = 50` always; never recalculate from data size. The grid skeleton is always full width.
- `editTextFields` is a contiguous list; columns must be built in order 0, 1, 2, … Never skip a column.
- `ensureColumnBuilt(col)` must be called before accessing `editTextFields[col * numRows + row]` for any column not guaranteed to be in the sync build batch.
- `insertCharsAt` trims trailing **blank** cells (`isBlank()`, not just `isEmpty()`) so island overflow never pushes trailing spaces forward.
- ViewFactory interface method names must not clash with MainActivity property getter names (use `provide*` prefix for catalogue-style accessors).
- `applyBackground(color)` sets only `bgColor` and `rootFrame` background — it never removes inserted images.
- `pushHistory()` should be called at direct user-action mutation entry points to avoid duplicate history frames.
- Keep `insertedImages` as the primary model; `bgImageUri`/`bgImageMatrix` are now mainly the runtime cache for the currently active image (gesture target + quick persistence path), not the main multi-image storage model.
- Style/setting changes (font, size, word-gap, colors) happen only while the tools panel is open and IME is hidden, so `rebuildGrid()` is acceptable there. Reserve `refreshGrid()` (no view destruction) for content edits made with the IME open.
- SCATTER must never grow the built-column range. Any new column-build hook (focus, scroll, buffer, paste expansion) MUST gate on `inputMode != InputMode.SCATTER`. Column growth is a SEQUENTIAL-only operation.

## Build

```bash
./gradlew.bat assembleDebug
./gradlew.bat installDebug
./gradlew.bat test
./gradlew.bat connectedAndroidTest
```
