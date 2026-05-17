# Architectural Refactoring Plan: Canvas-Based Text Engine

## 🤖 Instructions for the AI Agent
1. **Role:** You are acting as a Senior Android Engineer assisting in a major UI architecture refactoring.
2. **Workflow:** We will execute this plan **one phase at a time**. Do not jump ahead.
3. **Tracking:** As you complete tasks, update the `[ ]` checkboxes to `[x]` in your responses to maintain a clear state of progress.
4. **Design Doc:** Maintain a conceptual "Design Layout" in your memory. If the user's token limit is approaching, summarize the current state and modified variables so the user can copy-paste them into a new chat session to continue seamlessly.

---

## 🏛️ Architecture Context
* **Current State:** The app uses an Android `GridLayout` populated with hundreds of individual `EditText` views to simulate a 2D poem-writing canvas. This causes severe input latency and dropped keystrokes during rapid typing due to View churn, focus switching, and IME (Input Method Editor) binding delays.
* **Target State:** A custom `PoemCanvasView` that handles **100% of the UI rendering** via `Canvas.drawText()`. A single, hidden `EditText` (Ghost Input) will act as a proxy to summon the software keyboard and capture keystrokes.
* **Strict Constraint:** The data logic layer (`GridLogicHelper.kt`) is already pure Kotlin and decoupled. It expects and returns `List<List<String>>` (`columnData`). **Do not modify the core business logic or input mode rules (Scatter vs. Sequential).** Only change how the data is rendered and how inputs are captured.

---

## 📋 Execution Plan

### Phase 1: The Read-Only Rendering Engine
**Goal:** Create a custom View that can draw the existing `columnData` significantly faster than the `GridLayout`.

- [x] **Create `PoemCanvasView.kt`**: Subclass `android.view.View`.
- [x] **Setup Paint Objects**: Initialize `TextPaint` and `Paint` objects in the `init` block. Zero object allocations in `onDraw`.
- [x] **Implement `onMeasure`**: Sizes to `maxColumns * cellSize` × `numRows * cellSize`.
- [x] **Implement `onDraw`**: RTL nested loop with `canvas.drawText()`; FRONTIER_MARKER never rendered.
- [x] **State API**: `updateData()` for full metric recalc; `refreshContent()` for content-only; `setPreviewOverlay()` for composing hints.
- [x] **Integration**: `PoemCanvasView` is the sole renderer; `GridLayout`/`EditText` grid fully removed.

### Phase 2: The "Ghost Input" (IME Proxy)
**Goal:** Implement a single invisible `EditText` to handle keyboard events without the overhead of UI focus switching.

- [x] **Create the Ghost Input**: 1×1px hidden `EditText` added to `rootFrame` (`alpha=0`, no background, no cursor).
- [x] **Touch Interception**: `setupCanvasTouchListener()` on `poemCanvas` converts X/Y to cell index via `cellIndexAtTouch`.
- [x] **Focus Management**: `focusCell()` calls `ghostInput.requestFocus()` + `imm.showSoftInput(ghostInput, …)`.
- [x] **Input Routing**: Single `TextWatcher` on `ghostInput` with ZWS sentinel; routes all paths (typing, delete, paste, insert-shift, Enter, composing preview) through `columnData` + `poemCanvas.refreshContent()`.

### Phase 3: Virtual UI Elements (Cursor & Selection)
**Goal:** Since we removed `EditText`, we must manually draw the cursor and text selection highlights.

- [x] **Virtual Cursor**: `cursorIndex` state in `PoemCanvasView`; `setCursor(index)` / `startCursorBlink()` / `stopCursorBlink()`.
- [x] **Cursor Blinking**: `Handler` + `Runnable` toggles `cursorVisible` every 500 ms.
- [x] **Draw Cursor**: Underline at cell bottom drawn in `onDraw` when `cursorVisible`.
- [x] **Selection Highlights**: `selectionFrom`/`selectionTo` in `PoemCanvasView`; `setSelection()` / `clearSelectionHighlight()`.
- [x] **Draw Highlights**: Semi-transparent `selPaint` rect drawn behind each selected cell in `onDraw`.

### Phase 4: Integration, Cleanup & Reflow
**Goal:** Remove the old View-based system entirely and hook up the existing floating menus.

- [x] **Remove Old UI**: `GridLayout`, `editTextFields`, `builtColumns`, `gridEditorController`, `cellInputController`, and `makeCell()` all removed.
- [x] **Hook up Floating Menus**: `positionHandleAt`, `fastPositionHandleByIndex`, `positionSelectionOptionsView`, `fastCellIndexAtPoint`, `cellIndexAtScreenPoint` all use `poemCanvas.cellRect()` / `poemCanvas.cellIndexAtTouch()`.
- [x] **Verify Input Modes**: Ghost `TextWatcher` preserves all SCATTER/SEQUENTIAL branch logic; `insertCharsAt` / `reflowColumnData` / `placeFrontierMarker` unchanged.
- [x] **Cleanup**: `ensureBufferColumn` is a no-op; `placeFrontierMarker` operates only on `columnData`; composing preview via `poemCanvas.setPreviewOverlay()`.

---

**AI Agent Prompt Trigger:**
*"I have read the plan and understand the constraints. I am ready to begin Phase 1. Please provide the current `MainActivity.kt` and `GridLogicHelper.kt` context if needed, or tell me to generate the skeleton for `PoemCanvasView.kt`."*