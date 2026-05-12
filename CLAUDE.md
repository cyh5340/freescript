# poemeditor

Android app for composing text in vertical, right-to-left column order. Characters flow top-to-bottom inside a column; columns progress right-to-left.

All active UI is programmatic in `MainActivity.kt`. XML layouts and `PoemFragment` are legacy.

## Project Layout

```text
app/src/main/
|-- assets/fonts/LXGWWenKai-Regular.ttf
|-- java/com/poemeditor/
|   |-- MainActivity.kt          # primary app: editor, toolbar, sessions
|   |-- GridLogicHelper.kt       # pure data logic: setColumnChar, reflowColumnData, insertCharsAt
|   |-- SessionManager.kt        # file I/O + JSON helpers for session persistence
|   |-- SessionListActivity.kt   # full session list with search/date filters
|   `-- PoemFragment.kt          # unused legacy
`-- res/
    |-- values/themes.xml
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
|   |           `-- GridLayout RTL, numRows x MAX_COLUMNS
|   |               `-- EditText cells
|   `-- LinearLayout bottomPanel
|       |-- divider
|       |-- HorizontalScrollView punctToolbar    ← visible only while IME is open
|       `-- FrameLayout toolbar, 54dp, white
|           |-- LinearLayout mainBar             ← default visible: 工具 | divider | 標點
|           `-- LinearLayout punctBar            ← shown when 標點 tapped: ← | divider | HScrollView(punct)
`-- NestedScrollView allToolsPanel               ← Gravity.BOTTOM overlay, height = lastKeyboardHeight
    `-- LinearLayout content (vertical)
        |-- 字型: buildFontRow (font spinner + 字號 chip + 字距 chip, all inline)
        |-- text color swatch row
        |-- 排版: bg color swatch row
        |-- 寫作: mode chips only (連續輸入 / 灑花輸入)
        `-- 檔案: doc actions + recent session list
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
numColumns = MAX_COLUMNS = 50
```

`rebuildGrid()` computes this and rebuilds all cells. It is for settings/session changes, not keyboard open/close.

`refreshGrid()` diffs current `EditText` views against `columnData` and updates in place. Prefer it for content changes so the IME does not flicker.

## Current Helper Structure

- `withRestoring { ... }`: sets `isRestoring` safely around programmatic mutations.
- `persistCurrentState()`: saves both current session JSON and SharedPreferences.
- `advanceToNextCell(index, total)`: moves focus to `index + 1`, sets `cursorBefore = true`, scrolls column into view.
- `postRefreshFocusColumn(targetIndex, col)`: posts `refreshGrid()`, `focusCell(...)`, and `scrollToColumn(...)`.
- `refreshModeChips()`: only place that recolors writing mode chips.
- `columnDataToJson()`, `columnBreaksToJson()`, `loadColumnDataFromJson(...)`, `loadColumnBreaksFromJson(...)`: thin wrappers delegating to `SessionManager`.
- `setColumnChar(col, row, ch)`, `reflowColumnData(newNumRows)`, `insertCharsAt(...)`: thin wrappers delegating to `GridLogicHelper`.
- `buildPunctRow(...)` and `buildPunctButton(...)`: shared punctuation UI — used by both `punctToolbar` (IME bar) and `punctBar` (toolbar row).
- `buildColorSwatchRow(dp, colors, extra, onClick)`: shared factory for bg and text color swatch panels.
- `insertCharsAt(insertCol, insertRow, newChars)`: flat-slice insert-shift for SEQUENTIAL; captures content from `(insertCol, insertRow)` forward, writes `newChars`, re-appends displaced content. Never crosses a `columnBreaks` boundary.
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

- Stores logical insertion side in `cursorBefore`.
- Requests focus.
- Sets selection to 0.
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
- Occupied typing overwrites current cell and advances one cell.
- Occupied punctuation overwrites current cell and advances one cell.
- DEL removes at current row and pads with `""` to preserve row alignment.
- Enter splits before the focused row, moves the current row and following rows into the left column at row 0, and overwrites that target column instead of inserting a new one.
- Font/gap changes do not reflow content.

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
- empty raw text handles deletion/backspace behavior
- single char writes current cell and advances
- occupied SCATTER multi-char commit overwrites with the new char and advances one
- occupied SEQUENTIAL multi-char commit calls insert-shift via `performInsert(...)`
- paste/multi-line input distributes manually and records `columnBreaks`

`performInsert(...)` + `insertCharsAt(...)` handle Sequential insert-shift and do not cross explicit break boundaries.

## Punctuation

`buildPunctRow(dp)` is shared by `punctToolbar` (above keyboard when IME is open) and the `punctBar` inside the toolbar `FrameLayout` (shown when 標點 is tapped).

`insertPunct(punct)` behavior:

- empty cell or SCATTER mode: write punctuation directly, update view under `withRestoring`, advance one cell
- occupied SEQUENTIAL: call `performInsert(...)`, then `postRefreshFocusColumn(...)`

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
- Preserve mode differences. Most regressions come from accidentally sending SCATTER through SEQUENTIAL insert-shift paths.
- Keep `EditText` ids/tags unique across rebuilds.
- Keep custom background persistence app-owned, not dependent on external URI permission.
- `stableMaxHeight` must never be derived while the IME is open. The `OnGlobalLayoutListener` guards this with an `!imeVisible` check.
- The toolbar `FrameLayout` height and `allToolsPanel` height must match `lastKeyboardHeight` so the grid's paddingBottom transition is seamless.

## Build

```bash
./gradlew.bat assembleDebug
./gradlew.bat installDebug
./gradlew.bat test
./gradlew.bat connectedAndroidTest
```
