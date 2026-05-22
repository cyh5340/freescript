# freescript Fast Rules

Use this as the short operational checklist. `CLAUDE.md` is the fuller project map.

## Source Of Truth

- `columnData[col][row]` is the document model. `EditText` is only a view/input surface.
- Use `setColumnChar(col, row, ch)` for cell writes that may grow lists.
- Use `clearDocumentContent()` when clearing both `columnData` and `columnBreaks`.
- Use `columnDataToJson()`, `columnBreaksToJson()`, `loadColumnDataFromJson(...)`, and `loadColumnBreaksFromJson(...)` for persistence.

## Guarded Updates

- Wrap programmatic updates with `withRestoring { ... }` when they touch model/view text and must not trigger `TextWatcher` behavior.
- Use local `suppress` only around `editable.replace(...)` inside a cell watcher.
- Prefer `refreshGrid()` for content-only changes. Use `rebuildGrid()` only when layout/cell sizing changes.

## Modes

### SEQUENTIAL

- Empty tap redirects to the first empty sequential slot.
- Occupied typing inserts/shifts.
- Occupied punctuation inserts/shifts.
- DEL removes previous content and reflows.
- Enter splits before the focused row, inserts a new left column, and shifts existing columns.

### SCATTER

- Tap lands exactly where tapped.
- Occupied typing overwrites and advances one cell.
- Occupied punctuation overwrites and advances one cell.
- DEL removes at the row and pads the column with `""`.
- Enter splits before the focused row and writes the tail into the left column, overwriting that target column.
- Font/gap changes do not reflow content.

## Input Helpers

- `insertColumnBreak(cellCol, cursorRow)` handles all Enter/newline sources.
- `performInsert(...)` is Sequential insert-shift logic.
- `advanceToNextCell(...)` is simple one-cell focus advance.
- `postRefreshFocusColumn(...)` is refresh + focus + horizontal scroll.
- `insertPunct(...)` must preserve mode-specific behavior.

## Keyboard And Focus

- Do not use XML layouts for editor behavior.
- `MainActivity.kt` owns active UI.
- Touch listeners consume all events to suppress native cursor placement.
- Use `focusCell(...)` for deliberate focus changes.
- Do not rebuild grid on keyboard open/close.
- Keep manual keyboard avoidance via `translateForKeyboard(...)` / `applyScrollPadding()`.

## Background Images

- Picker uses `ActivityResultContracts.OpenDocument()`.
- On pick, copy image bytes into `filesDir/backgrounds/{sessionId}.bg`.
- Store the app-owned `file://` URI in `bgImageUri`.
- Persist immediately with `persistCurrentState()`.
- Do not clear `bgImageUri` on a transient load/decode failure.

## Smoke Test Before Push

Run:

```bash
./gradlew.bat assembleDebug
```

Then manually test:

- Sequential empty typing, occupied insertion, punctuation, Enter, DEL
- Scatter empty typing, occupied overwrite, punctuation overwrite, Enter overwrite-line behavior, DEL
- custom background pick, app restart, session reload
