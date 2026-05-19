# Refactoring Cleanup Checklist

Date: 2026-05-18

Goal: reduce redundant/mirrored code, remove half-finished over-engineered extractions, and consolidate ownership boundaries while preserving or improving editor performance.

## Principles

- Preserve current typing behavior in SEQUENTIAL, SCATTER, HORIZONTAL, and FREESTYLE modes.
- Do not add indirection to hot paths unless it removes heavier work.
- Keep `TextWatcher.afterTextChanged` and `PoemCanvasView.onDraw` allocation-light.
- Prefer one canonical state owner over copying data between mirrored structures.
- Refactor in small compile-verified steps.

## Current Findings

- [x] Remove or fully wire unused controller shells:
  - [x] `TextInputController.kt`
  - [x] `CanvasModeController.kt`
  - [x] `FreestyleController.kt`
  - [x] `InputFieldEditController.kt`
- [x] Remove FREESTYLE global data mirroring between `TextBoxInstance.columnData` / `columnBreaks` and `MainActivity.columnData` / `columnBreaks`.
- [x] Reduce pure pass-through delegate wrappers in `MainActivity`.
- [x] Consolidate `SessionManager` around `SessionDocument` instead of long overload chains.
- [x] Clarify ownership of selection overlay view creation, positioning, and behavior.
- [ ] Treat `PoemCanvasView` draw-loop deduplication as lower priority because it is a hot path.

## Phase 1: Delete Dead Extraction Shells

Target: remove architecture weight that currently provides no runtime value.

- [x] Confirm no references to `TextInputController`, `CanvasModeController`, `FreestyleController`, or `InputFieldEditController`.
- [x] Delete unused controller files.
- [x] Remove any stale comments/docs that describe these shells as active.
- [x] Compile after deletion.

Verification:

- [x] `.\gradlew.bat :app:compileDebugKotlin`
- [x] Confirm no unresolved imports or references.

## Phase 2: Active Field State Model

Target: eliminate FREESTYLE deep-copy mirroring and make editor logic operate on one canonical active field.

Proposed model:

```kotlin
interface EditableField {
    val columnData: MutableList<MutableList<String>>
    val columnBreaks: MutableSet<Int>
    var rowCount: Int
    var colCount: Int
    var fontIndex: Int
    var fontSizeSp: Float
    var wordGapDp: Float
    var gridTextColor: Int
    var inputMode: InputMode
}
```

Checklist:

- [x] Replace `activateFreestyleBox()` deep copy with direct reference assignment (`columnData = box.columnData`).
- [x] Replace `deactivateFreestyleBox()` copy-back with reference detach (`columnData = mutableListOf()`).
- [x] Update save/history paths to snapshot the canonical active field directly (removed sync-back blocks from `snapshotState` and `saveSession`).
- [x] Remove redundant `columnData.clear()` / copy-back blocks used only for mirroring.

Performance expectation:

- Fewer full-grid deep copies when selecting a box, changing font/size/gap/color, saving, and undo snapshotting.

Verification:

- [ ] Fast typing in FREESTYLE active box.
- [ ] Switch between multiple FREESTYLE boxes.
- [ ] Change font, size, word gap, and text color per box.
- [ ] Save, reopen, undo, and redo FREESTYLE documents.
- [ ] Confirm no stale text appears after deactivating a box.

## Phase 3: Simplify MainActivity Delegates

Target: reduce redundant one-line wrappers where they do not improve readability.

- [x] Inline trivial image delegate wrappers:
  - [x] `updateActiveImageRuntimeState`
  - [x] `renderInsertedImages`
  - [x] `findTouchedImageIndex`
  - [x] `activateImageAt`
  - [x] `syncActiveImageFromList`
- [x] Inline trivial keyboard delegate wrappers where call sites remain clear.
- [x] Inline trivial selection delegate wrappers where call sites remain clear.
- [x] Keep wrappers only when they encode extra behavior, such as `cutSelectedText()` pushing history before delegating.

Verification:

- [x] Compile.
- [ ] Insert, select, move, and delete images.
- [ ] Selection copy/cut/paste still works.
- [ ] Tools/keyboard switching still works.

## Phase 4: Consolidate Session Serialization

Target: make `SessionDocument` the single save/default-session API.

- [x] Keep `SessionManager.saveSession(filesDir, doc: SessionDocument)`.
- [x] Remove or make private the long-parameter `saveSession(...)` overload.
- [x] Keep `SessionManager.ensureDefaultSession(filesDir, doc: SessionDocument)`.
- [x] Remove or make private the long-parameter `ensureDefaultSession(...)` overload.
- [x] Extract shared image JSON writing so `insertedImagesToJsonString()` and session save do not duplicate array construction.
- [ ] Consider a `SessionDocument.fromJson()` helper only if it reduces duplication in load paths without hiding important legacy behavior.

Verification:

- [ ] New session creation.
- [ ] Existing session load.
- [ ] Rename session.
- [ ] Delete session.
- [ ] Load legacy JSON with `bgImageUri` / `bgImageMatrix`.
- [ ] Confirm session list word/image counts remain correct.

## Phase 5: Selection Overlay Ownership

Target: avoid split ownership between `ViewFactory`, `SelectionController`, and `MainActivity`.

Option A: controller owns selection views.

- [ ] Move `buildSelectionOptionsView`, `buildHandlePasteView`, and `buildSelectionHandle` from `ViewFactory` to `SelectionController` or a small `SelectionOverlayViewFactory`.
- [ ] Let `SelectionController` create, position, show, and hide overlay views.
- [ ] Keep `MainActivity.dispatchTouchEvent` as the event source only.

Option B: factory owns construction, controller owns all behavior.

- [x] Keep view creation in `ViewFactory`.
- [x] Ensure only `SelectionController` positions, shows, hides, and updates selection overlay state.
- [x] Remove extra selection UI manipulation from `MainActivity` where possible.

Recommendation:

- [x] Choose Option B first for lower risk.

Verification:

- [ ] Long press selection.
- [ ] Drag start and end handles.
- [ ] Copy, cut, paste.
- [ ] Select line, paragraph, and all.
- [ ] Selection after resize/reflow.

## Phase 6: PoemCanvasView Cleanup, Only If Needed

Target: reduce repeated drawing mechanics without harming frame time.

- [ ] Profile current draw behavior before changing.
- [ ] Identify repeated selection/cursor/cell coordinate logic.
- [ ] Extract tiny helpers only if they allocate nothing during `onDraw`.
- [ ] Avoid object creation inside draw loops.
- [ ] Avoid unifying vertical/horizontal drawing if it makes coordinate logic harder to audit.

Verification:

- [ ] Visual check in VERTICAL mode.
- [ ] Visual check in HORIZONTAL mode.
- [ ] Visual check in FREESTYLE mode.
- [ ] Cursor blink still works.
- [ ] Selection highlight still works.
- [ ] Screenshot crop still captures expected output.

## Final Regression Checklist

- [ ] Build: `.\gradlew.bat :app:compileDebugKotlin`
- [ ] SEQUENTIAL typing, insert, delete, paste.
- [ ] SCATTER typing, island behavior, and space-cell behavior.
- [ ] Enter / paragraph ending marker behavior.
- [ ] Undo/redo for text, settings, images, and FREESTYLE boxes.
- [ ] New, rename, switch, reopen, and delete sessions.
- [ ] Selection handles, menus, copy, cut, paste.
- [ ] Multi-image insert, select, move, remove.
- [ ] Keyboard/tools mutual exclusion.
- [ ] Screenshot flow.
- [ ] FREESTYLE create, resize, move, activate, deactivate, delete.

## Stop Conditions

- [ ] Stop if a refactor increases callback count on hot input paths without eliminating heavier work.
- [ ] Stop if `PoemCanvasView.onDraw` starts allocating per cell or per character.
- [ ] Stop if an extraction requires a large callback interface but does not move real ownership.
- [ ] Stop if manual testing reveals behavior drift in IME composition, paste, or paragraph markers.
