# AR Translation Quality Index (TQI) & Real-World Verification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the 100-Point AR Translation Quality Index (TQI) evaluator, 4 real-world screen archetypes (Web Search, Messenger, Documentation, App Store/Mixed UI), host-based benchmark test suite, and on-device OnePlus 13 verification runner.

**Architecture:** A multi-dimensional evaluator scoring Geometry (25), Inpainting (25), Typography/WCAG (25), and Semantics (25) over real-world accessibility and raster fixtures on host JVM, paired with an on-device ADB verification workflow strictly targeting OnePlus 13 (`a36d1946`).

**Tech Stack:** Kotlin, JUnit 4, Android Graphics / Rect math, JSON parsing, ADB shell, Google ML Kit NMT.

**Spec:** `docs/superpowers/specs/2026-09-14-ar-translation-quality-index-and-real-world-verification-design.md`

## Global Constraints
- Target Hardware strictly isolated to OnePlus 13 (`a36d1946`).
- Zero copied GPL code; strict dependency audit compliance.
- DRY and zero-allocation in draw loops; host tests must run in < 3.0 seconds.
- Minimum acceptable TQI score is 85/100 for all archetypes.

---

### Task 1: Screen Fixtures Data Models & Archetype Datasets

**Files:**
- Create: `app/src/test/kotlin/com/translive/app/engine/tqi/ScreenFixture.kt`
- Create: `app/src/test/resources/fixtures/archetype_web_search.json`
- Create: `app/src/test/resources/fixtures/archetype_messenger_chat.json`
- Create: `app/src/test/resources/fixtures/archetype_documentation.json`
- Create: `app/src/test/resources/fixtures/archetype_app_store_grid.json`
- Test: `app/src/test/kotlin/com/translive/app/engine/tqi/ScreenFixtureLoaderTest.kt`

**Interfaces:**
- Produces: `ScreenFixture(id: String, screenWidth: Int, screenHeight: Int, nodes: List<ExtractedTextNode>, expectedAnchors: List<ExpectedAnchor>, noiseIds: Set<String>, protectedEntities: List<String>)`

- [x] **Step 1: Write failing test for ScreenFixture loading**
- [x] **Step 2: Run test to verify it fails**
- [x] **Step 3: Implement ScreenFixture and ScreenFixtureLoader**
- [x] **Step 4: Create JSON datasets for the 4 archetypes (Web Search, Messenger, Docs, App Store Grid)**
- [x] **Step 5: Verify tests pass**
- [x] **Step 6: Commit**

---

### Task 2: 100-Point TQI Scoring Engine

**Files:**
- Create: `app/src/test/kotlin/com/translive/app/engine/tqi/TqiScore.kt`
- Create: `app/src/test/kotlin/com/translive/app/engine/tqi/TqiEvaluator.kt`
- Test: `app/src/test/kotlin/com/translive/app/engine/tqi/TqiEvaluatorTest.kt`

**Interfaces:**
- Consumes: `ScreenFixture`, `List<ExtractedTextNode>`, translation outputs
- Produces: `TqiScore(total: Float, geometryScore: Float, inpaintingScore: Float, typographyScore: Float, semanticScore: Float, issues: List<String>)`

- [x] **Step 1: Write unit tests for GeometryScorer (collision, left-alignment, screen containment)**
- [x] **Step 2: Write unit tests for Typography & WCAG contrast scorer**
- [x] **Step 3: Write unit tests for SemanticScorer (token leak detection, entity preservation)**
- [x] **Step 4: Implement TqiEvaluator scoring logic**
- [x] **Step 5: Run tests to verify all scorer tests pass**
- [x] **Step 6: Commit**

---

### Task 3: Tier 1 Host Benchmark Test Suite & Test Audit

**Files:**
- Create: `app/src/test/kotlin/com/translive/app/engine/tqi/TqiHostBenchmarkTest.kt`
- Modify: `app/src/test/kotlin/com/translive/app/service/ScreenTextNoiseFilterTest.kt`
- Test: `app/src/test/kotlin/com/translive/app/engine/tqi/TqiHostBenchmarkTest.kt`

**Interfaces:**
- Consumes: `ScreenFixtureLoader`, `TqiEvaluator`, `NativeScreenTextExtractor`, `ScreenTextNoiseFilter`, `BrandEntityShield`
- Produces: Full benchmark execution on all 4 archetypes validating TQI >= 85/100

- [x] **Step 1: Implement TqiHostBenchmarkTest executing the 4 archetypes through the extraction and scoring pipeline**
- [x] **Step 2: Run test and verify all 4 archetypes achieve TQI >= 85**
- [x] **Step 3: Audit existing test suites to remove redundant synthetic bloat while ensuring 100% regression coverage**
- [x] **Step 4: Run full `.\gradlew testDebugUnitTest` and verify rapid clean execution**
- [x] **Step 5: Commit**

---

### Task 4: Tier 2 On-Device Smoke Verification on OnePlus 13 (`a36d1946`)

**Files:**
- Create: `scripts/verify_on_device_tqi.ps1`
- Test: Live execution on OnePlus 13 (`a36d1946`)

**Interfaces:**
- Consumes: Connected OnePlus 13 (`a36d1946`), `ScreenTranslateOverlayService`
- Produces: Live on-device screenshot artifact, UI hierarchy dump, logcat audit, verification summary

- [ ] **Step 1: Create automated on-device verification script `scripts/verify_on_device_tqi.ps1` targeting `-s a36d1946`**
- [ ] **Step 2: Ensure test web page or sample is loaded in Firefox**
- [ ] **Step 3: Execute verification script, trigger AR overlay, capture post-translation screenshot**
- [ ] **Step 4: Inspect post-translation screenshot to ensure zero full-screen black erasing, zero token leaks, and crisp typography**
- [ ] **Step 5: Update documentation (PROJECT_STAGE.md, ROADMAP.md, HANDOFF.md) and commit**
