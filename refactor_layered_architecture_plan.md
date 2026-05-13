# Layered Refactor Plan (Current Codebase Tracker)

Date: 2026-05-12  
POC: AdaL  
TL;DR: **Refactor is complete enough to stop.** The high-value extractions are done. Remaining steps have diminishing returns and increasing risk. Recommendation: close out the plan and work inline from here.

---

## 1) Current Architecture Snapshot (Actual)

### Implemented
- **State Layer**
  - `EditorViewModel.kt` owns undo/redo stack and session-facing orchestration.
  - `EditorStateModels.kt` defines `InsertedImageState` and `EditorHistoryState`.
- **Persistence Layer**
  - `SessionRepository.kt` used by `EditorViewModel`.
  - `SessionManager.kt` provides JSON conversion + file I/O helpers.
  - `ScreenshotHelper.kt` owns bitmap capture and MediaStore/legacy save logic.
- **Grid Layer**
  - `GridEditorController.kt` owns lazy column construction: `buildInitialColumns`, `ensureColumnBuilt`, `buildColumn`.
  - `MainActivity` delegates all column build calls to the controller.
- **Cell Input Layer**
  - `CellInputController.kt` owns per-cell touch and hardware key listeners.
  - `lastTapIndex` / `lastTapTime` (double-tap state) live in the controller.
  - `MainActivity.makeCell` delegates both listeners to the controller via lambdas.
  - `TextWatcher` + `OnEditorActionListener` remain in `MainActivity` (intentional — see below).
- **UI Layer**
  - `ViewFactory.kt` builds all views; `MainActivity` implements `ViewFactory.Callbacks`.
  - Tools panel sections are all inline rows (字型, 底色, 插入, 輸入模式, 檔案).
  - Session name shown in 檔案 inline row (`docFileNameRef`), not in toolbar.

---

## 2) Phase Progress

### Phase 0 — Baseline contracts/models
**Status:** ✅ Complete

### Phase 1 — SessionRepository extraction
**Status:** ✅ Complete

### Phase 2 — EditorViewModel migration
**Status:** ✅ Complete

### Phase 3 — GridEditorController extraction
**Status:** ✅ Complete (lazy column build fully extracted; `rebuildGrid`/`refreshGrid` orchestration intentionally stays in `MainActivity` — see assessment below)

### Phase 4 — CellInputController extraction (was Step A)
**Status:** ✅ Complete (A1 slice: touch + hardware key listeners extracted)

### Phase 5 — Further controller extractions (Steps B/C/D)
**Status:** 🛑 Assessed and closed — not worth pursuing (see Section 4)

---

## 3) What Remains in `MainActivity`

After all extractions, the remaining `MainActivity` blocks are:

1. **`TextWatcher`** — every-keystroke hot path; routes through SEQUENTIAL/SCATTER logic, composing preview, insert-shift, paste distribution. Most complex block in the file.
2. **Selection overlay** — handle hit-test/drag in `dispatchTouchEvent`, highlight diff, menu/bubble placement. Deeply coupled to `dispatchTouchEvent` which must stay in `MainActivity`.
3. **Image gestures** — two-finger pinch/pan in `dispatchTouchEvent`, `syncActiveImageFromList`, avatar panel.
4. **Keyboard/inset orchestration** — insets listener, padding/translation scheduling, tools↔IME mutual exclusion.

---

## 4) Remaining Steps — Assessment

### Step A remaining: Extract `TextWatcher` into `CellInputController`
**Verdict: Not worth it.**
- TextWatcher is the highest-risk block. Every keystroke goes through it; SEQUENTIAL/SCATTER routing, composing preview, multi-char commits, paste, and insert-shift all live here.
- Extracting it requires passing 15+ lambdas or a large interface — adding indirection to the hottest path.
- The touch/key extraction (A1) captured the easy wins. The watcher tightly references `suppress` (local to `makeCell`), `index` (closure), and multiple mutable activity fields — the coupling is real, not accidental.
- Bugs in this path are hard to diagnose across a controller boundary.

### Step B: Extract `SelectionOverlayController`
**Verdict: Not worth it.**
- Selection drag entry point is `dispatchTouchEvent`, which must stay in `MainActivity`. The controller would receive all events through a callback, adding a layer with no reduction in coupling.
- Selection code is stable — it hasn't caused regressions in months. Extracting stable, infrequently-changed code for its own sake is pure overhead.

### Step C: Extract `ImageCanvasController`
**Verdict: Not worth it.**
- Image rendering is already self-contained in `MainActivity`'s image section. It doesn't cause maintenance problems.
- Two-finger gesture routing also lives in `dispatchTouchEvent`, same coupling issue as selection.
- Avatar panel refresh (`refreshInsertedImagePanel`) requires direct access to `rootFrame` and `insertImageContainer` — real view coupling, not accidental.

### Step D: Extract `KeyboardInsetObserver`
**Verdict: Not worth it.**
- Inset observation needs `window`, `rootFrame`, and `mainScrollView` — all Activity-owned. An extracted class would just hold callbacks into `MainActivity`, adding 2 layers of indirection for ~50 lines.
- No testability benefit since it's entirely Android-platform coupled.

### Phase 3 remaining: Move `rebuildGrid`/`refreshGrid` into `GridEditorController`
**Verdict: Not worth it.**
- `rebuildGrid` reads `stableMaxHeight`, `fontSizeSp`, `wordGapDp`, `selectedTypeface`, `gridTextColor`, `columnData`, `columnBreaks`, `inputMode` — all `MainActivity` fields. Passing all of these as constructor params or lambdas is more coupling, not less.
- `refreshGrid` is already a tight diff loop; the controller would just be a thin wrapper.

---

## 5) Final Recommendation

**Stop the structured refactor here.**

The extractions that mattered are done:
- Session/persistence complexity → `SessionRepository` / `EditorViewModel`
- Undo/redo → `EditorViewModel`
- Column lazy-build → `GridEditorController`
- Cell touch/key → `CellInputController`
- Screenshot → `ScreenshotHelper`

`MainActivity` is still the largest file, but its remaining content is genuinely activity-coupled: it reacts to Android lifecycle events, owns the view hierarchy root, and routes between subsystems. That's the correct role for an Activity. Further extraction would produce controllers that are just thin callback wrappers — more files, same coupling, harder to read.

Future work should be done inline. The refactor plan is closed.

---

## 6) Verification Checklist (for any future changes touching hot paths)

- Build: `.\gradlew.bat :app:compileDebugKotlin`
- Manual regression:
  - SEQUENTIAL typing / insert / delete / paste
  - SCATTER island and space-cell behavior
  - Undo/redo on content + settings + images
  - Session new / rename / switch / reopen
  - Selection handles / menus / copy-cut-paste
  - 選取整段 across paragraph boundaries (regression: was selecting everything after SCATTER→SEQUENTIAL switch)
  - Multi-image select + gesture on touched image
  - Keyboard/tools mutual exclusion (no grid jump)
