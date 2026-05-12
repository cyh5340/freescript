# poemeditor

Android app for composing text in vertical, right-to-left column order. Characters flow top-to-bottom inside a column; columns progress right-to-left.

All active UI is programmatic in `MainActivity.kt`. XML layouts and `PoemFragment` are legacy.

## Project Layout

```text
app/src/main/
|-- assets/fonts/LXGWWenKai-Regular.ttf
|-- java/com/poemeditor/
|   |-- MainActivity.kt          # primary app: editor, toolbar, sessions; implements ViewFactory.Callbacks
|   |-- ViewFactory.kt           # all view-building logic; Callbacks interface for MainActivity↔UI decoupling
|   |-- AppConfig.kt             # user-selectable palette constants: BG_COLORS, TEXT_COLORS, FONT_SIZE_LIST, PUNCT_LIST
|   |-- GridLogicHelper.kt       # pure data logic: setColumnChar, reflowColumnData, insertCharsAt
|   |-- SessionManager.kt        # file I/O + JSON helpers for session persistence
|   |-- SessionListActivity.kt   # full session list with search/date filters
|   `-- PoemFragment.kt          # unused legacy
`-- res/
    |-- values/themes.xml
    |-- values/colors.xml        # fixed UI chrome colors (panel_bg, chip_active, selection_highlight, etc.)
    `-- values-night/themes.xml
```

SDK: compile 33, min 24, Java 8, Kotlin 1.7.20.

## Current View Hierarchy

```text
FrameLayout rootFrame
|-- ImageView bgImageView
|   `-- Added lazily at child index 0, CENTER_CROP, behind all UI
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
|       |-- 字型: buildFontRow (font spinner + 字號 chip + 字距 chip, all inline)
|       |-- text color swatch row
|       |-- 排版: bg color swatch row
|       |-- 寫作: mode chips only (連續輸入 / 灑花輸入)
|       `-- 檔案: doc actions + recent session list
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

**Grid height stability:**
- `stableMaxHeight` is captured once in `OnGlobalLayoutListener` when the IME is absent, before `rebuildGrid` is ever called.
- All `rebuildGrid` calls use `stableMaxHeight` as `availH`, so `numRows` never changes regardless of keyboard or panel state.
- `rebuildGrid(isInitialBoot = true)` suppresses `focusCell`/`translateForKeyboard` on first build to prevent unwanted keyboard popup.

## ViewFactory / Callbacks Pattern

`ViewFactory(context, cb: Callbacks)` owns all view construction. `MainActivity` implements `ViewFactory.Callbacks`.

- **No logic lives in ViewFactory** — it calls `cb.*` for all state reads and event responses.
- `ViewFactory.Callbacks` interface methods: `provideFontCatalogue()`, `getFontIndex()`, `getFontSizeSp()`, `getWordGapDp()`, `getSelectedTypeface()`, `getInputMode()`, `onToolsToggle()`, `onPunctInsert()`, `onFontSelected()`, `onFontSizeChanged()`, `onWordGapChanged()`, `onBgColorSelected()`, `onImagePickerRequested()`, `onTextColorSelected()`, `onModeSelected()`, `onCopySelection()`, `onCutSelection()`, `onPasteAtSelection()`, `onSelectEntireLine()`, `onSelectEntireParagraph()`, `onSelectAll()`, `onHandlePasteClicked()`, `onRenameSession()`, `onNewSession()`, `onShowAllSessions()`, `onCollapsePanel()`.
- After `viewFactory.buildBottomPanel(dp)`, `MainActivity` copies back all refs: `allToolsPanel`, `punctToolbar`, `toolsCell`, `docListContainer`, `fontSpinnerRef`, `fontSizeLabelRef`, `gapValueLabelRef`, `modeChipContainer`.
- Selection views are also built via ViewFactory: `buildSelectionHandle(isStart, dp)`, `buildSelectionOptionsView(dp)`, `buildHandlePasteView(dp)`.
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

`rebuildGrid()` computes this and rebuilds all cells. It is for settings/session changes, not keyboard open/close.

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
- `buildGeneration: Int` — incremented on every `rebuildGrid`; used to invalidate stale closures.
- `incrementalBuildRunnable` — kept for cancellation safety on rebuild; unused in normal operation (no async loop).

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
- `fillGapsForSequentialMode()`: when switching SCATTER→SEQUENTIAL, fills every empty slot between first and last occupied cell with a half-width space so auto-advance never skips a gap.
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

- Empty tap redirects to first empty cell in column-major flow.
- Occupied typing uses insert-shift.
- Occupied punctuation uses insert-shift.
- DEL removes previous content and calls `reflowColumnData(numRows)`.
- Enter splits before the focused row, inserts a new column on the left, and shifts existing columns left/preserves them.
- Switching SCATTER -> SEQUENTIAL calls `fillGapsForSequentialMode()`.

### SCATTER

Label in UI: 灑花輸入.

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

Solid swatches call `applyBackground(color)`:

- set `bgColor`
- clear `bgImageUri`
- hide `bgImageView`
- persist immediately

Custom image flow:

- picker is `ActivityResultContracts.OpenDocument()`
- try `takePersistableUriPermission`
- copy selected image bytes to `filesDir/backgrounds/{currentSessionId}.bg`
- store app-owned `file://` URI in `bgImageUri`
- call `loadBgImageFromUri(...)`
- persist immediately with `persistCurrentState()`

`loadBgImageFromUri(...)` supports `file://` and provider URIs. It hides the image on load failure but does not clear `bgImageUri`, so a transient failure cannot erase the saved path.

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
  "inputMode": "SEQUENTIAL"
}
```

Important functions:

- `saveSession(id, name)`: writes content and settings.
- `loadSessionFile(id)`: saves current, loads target, applies settings, rebuilds.
- `newSession()`: saves current, clears model, applies defaults, rebuilds.
- `ensureDefaultSession()`: creates initial session if needed.
- `refreshDocPanel()`: shows up to 3 recent sessions.
- `SessionListActivity`: full searchable/filterable session list.

## Toolbar

`toolbar` is a `FrameLayout` (54dp tall) with two alternating children:

- `mainBar` (default VISIBLE): `buildCategoryCell("工具")` | `divider` | `buildCategoryCell("標點")`
- `punctBar` (default GONE): `←` back button (54dp wide) | `divider` | `HScrollView(buildPunctRow(dp))`

Tapping 工具 calls `switchToTools()` / `switchToKeyboard()`. Tapping 標點 swaps bar visibility. No persistent state flag for the bar swap — purely view visibility toggling via captured `mainBarRef`/`punctBarRef` locals.

`buildCategoryCell(label, toolbarH, dp, onClick)` creates a weight-1f `LinearLayout` with centered label text. `toolsCell` holds a reference to the 工具 cell for background highlight in `switchToTools/switchToKeyboard`.

## Tools Panel

`allToolsPanel` is a `NestedScrollView` overlaid on `rootFrame` at `Gravity.BOTTOM`. Height is set to `lastKeyboardHeight` when shown (fallback 280dp). It is GONE by default and toggled by `switchToTools/switchToKeyboard`.

Sections (top to bottom):

- **字型**: `buildFontRow(dp)` — font spinner + 字號 chip + 字距 chip, all in one horizontal row with `rowDivider`s
- Text color swatch row (horizontal scroll)
- **排版**: bg color swatch row (horizontal scroll, includes 自訂 image picker)
- **寫作**: mode chips only (連續輸入 / 灑花輸入)
- **檔案**: action buttons (更名 / 新增 / 全部) + recent session list (`docListContainer`)

Font size and word gap use `showPopupSeekbar(anchor, max, initial, format, onChange)` — a `PopupWindow` with a `SeekBar` that floats above the tapped chip.

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

## Build

```bash
./gradlew.bat assembleDebug
./gradlew.bat installDebug
./gradlew.bat test
./gradlew.bat connectedAndroidTest
```
