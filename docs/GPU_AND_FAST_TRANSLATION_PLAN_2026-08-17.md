# GPU and fast offline translation plan

## Non-negotiable baseline

- Snapdragon 8 Elite / Adreno 830 (OnePlus 13) is the reference profile.
  Its current GGUF OpenCL path, embedded Adreno kernels, LiteRT GPU route and
  MNN OpenCL path must remain intact. New work is additive and is accepted
  only when it does not regress this profile.
- No separate APK or language model is made for a Snapdragon generation.
  The APK contains compatible native backends; a versioned device profile
  selects safe build/runtime parameters after real capability checks and a
  model benchmark.
- CPU remains the fallback only for driver failure or a measured loss. It is
  not a substitute for implementing a working Adreno GPU path.

## Phase 1 — Adreno compatibility and performance

1. Audit current pinned llama.cpp/MNN revisions and Android OpenCL loader
   integration against upstream and PocketPal's Android bridge.
2. Keep `GGML_OPENCL_USE_ADRENO_KERNELS` and embedded kernels enabled.
   The current universal build targets OpenCL API 3.0. It is retained as the
   Adreno 830 reference build; it must not be silently reused as the only
   compatibility/performance assumption for older drivers.
3. Add device profile data for the following families, without overriding
   Adreno 830: Adreno 630/640/650/660 (Snapdragon 845–888), 730/740/750
   (8 Gen 1–8 Gen 3), 830 (8 Elite), and future Adreno devices identified by
   runtime capability rather than a hard-coded marketing name.
4. Implement a model-aware benchmark: CPU and OpenCL load time, prompt
   processing, generation speed, memory failure and stability. Persist the
   winning backend per device driver + model fingerprint.
5. Package a compatible OpenCL target profile in addition to the current
   Adreno 830 profile when device validation proves the OpenCL 3.0 build is
   slower or rejected on an earlier GPU. Profile selection is based on the
   actual driver/API and a benchmark, not on the Snapdragon marketing label.
6. Prefer full GPU offload when it is valid; use a validated partial offload
   profile when driver/model limits prevent it. Show the actual result in
   diagnostics, never a guessed backend.
7. Build signed release APK, verify its signature/version, publish release,
   then test on available Snapdragon hardware.

Acceptance: Adreno 830 behaviour remains unchanged; every other supported
Adreno has a deterministic OpenCL attempt and recorded benchmark result.

## Phase 2 — compact fast offline translation packages

The target architecture is based on the functional separation researched in
`DavidVentura/offline-translator`: OCR is only input extraction for images;
compact offline NMT produces the fast translation; LLM is a separate quality
operation. It applies to all product surfaces:

| Surface | Input stage | Fast route | Quality route |
| --- | --- | --- | --- |
| Text | text input | local NMT package | LLM revise/translate |
| Dialogue | ASR | local NMT package | LLM revise result |
| Camera/photo | OCR | local NMT package | LLM revise selected blocks |
| Screen | capture + OCR | local NMT package | LLM revise selected blocks |

1. Select an Android-compatible compact NMT runtime and language-pair package
   format with reproducible source, license, checksum and offline test corpus.
2. Add a collapsed Fast translation packages group in Models. Downloading a
   source-target pair fetches only its missing reusable assets; each package
   can also be managed individually.
3. In every surface, selected languages immediately show one action to fetch
   the missing fast package. No frame/audio callback may silently start a
   network download.
4. The default result is fast NMT. Existing ML Kit fast routing is treated as
   a compatibility path until it is replaced by the dedicated package runtime.
5. Build, verify and publish a release APK.

## Phase 3 — LLM improve action and visual transition

1. Add one app-wide translation policy setting with three equal modes:
   - **Fast** — only the compact offline NMT package;
   - **Fast + improve manually** — show compact NMT output immediately and
     expose the magic-wand action;
   - **LLM immediately** — skip the fast result and translate directly with
     the selected local LLM.
   The setting applies consistently to text, dialogue, camera/photo and
   screen translation. It is not merely an "Improve" button preference.
2. Fast + improve preserves source, languages and fast output. The LLM prompt
   revises semantics, terminology and naturalness rather than blindly
   retranslating a detached string.
3. Add an accessible magic-wand action to text, dialogue transcript, camera
   capture and screen result. It has loading/cancel/failure states.
4. Animate the replacement as a left-to-right clipped gradient/pixel reveal;
   respect Android animator duration scale and reduced-motion accessibility.
5. Build, verify and publish a release APK.

## Evidence sources

- PocketPal Android project: https://github.com/a-ghorbani/pocketpal-ai
- PocketPal llama bridge requirements: https://www.npmjs.com/package/@pocketpalai/llama.rn
- llama.cpp OpenCL Android guidance: https://github.com/ggml-org/llama.cpp/blob/master/docs/backend/OPENCL.md
- offline-translator architecture (GPL-3.0; no code is copied): https://github.com/DavidVentura/offline-translator
