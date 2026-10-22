# Product Vision

## One sentence

Parlex is a privacy-first, on-device translator that lets a person read, hear
and answer in another language without sending their text, screen or voice to a
server.

## Who it is for

1. **Travellers and expats** who need menus, signs, documents, screens and
   spontaneous conversation in places with poor or untrusted connectivity.
2. **Privacy-sensitive users** who cannot upload screenshots or speech to a
   cloud service.
3. **Power users and researchers** who want to select local models, see runtime
   diagnostics and understand the trade-offs between speed and quality.

## Product principles

1. **Local by default.** Translation, OCR, STT and TTS should work in airplane
   mode when the chosen model pack is installed. Network is for acquiring models
   or optional data, not for routine translation.
2. **One clear action per surface.** The camera has one shutter, the screen
   overlay has one translate gesture, dialogue has one start/stop control.
3. **Content first.** Controls float above content; they do not compete with it.
   Translation should replace source text in place, not decorate it.
4. **Honest state.** Never claim a language pair, model or feature is ready when
   it is not. Show detected language, missing packs and fallbacks.
5. **Graceful degradation.** If the best model is absent, use the best available
   local path and explain the fallback; never silently produce empty output.
6. **Accessible by construction.** TalkBack, large text, reduced motion and
   one-hand reach are acceptance criteria, not later polish.
7. **Measured quality.** Every major feature has a reproducible benchmark or a
   manual acceptance scenario. Opinions do not replace measurements.

## Experience pillars

| Pillar | What it means |
|---|---|
| Instant text | Type, paste or share text and get a local translation quickly |
| Camera | Point, capture or import a photo; translated text stays inside the source geometry |
| Screen | Read another app in place through an accessibility shortcut with a quiet, reversible overlay |
| Voice | Natural two-way dialogue with local ASR/TTS, echo control and visible state |
| Trust | No telemetry, explicit permissions, local data retention and inspectable models |

## Non-goals

- A cloud translation service or account system.
- Social feed, ads, telemetry, engagement gamification.
- Re-hosting model weights without a compatible upstream license.
- A full desktop OS replacement or a general-purpose chatbot.
- Supporting every Android OEM and every language at equal quality in the near
  term; coverage grows in measured phases.

## Success signals

Near term:

- A new user can install a signed beta, open camera/text/screen and get a
  translated result without a crash or a silent no-op.
- Repeated screen shortcut and floating-button use work at least 10 times in a
  row.
- Gallery imports detect the source language and never show a stale pair.
- CI is green on every public `main` push.

Mid term:

- A device QA matrix covers Pixel, Samsung and OnePlus across API 26/33/36.
- Screen and camera overlays pass TalkBack basics and reduced-motion checks.
- Model integrity is fail-closed and documented.
- A reproducible quality benchmark exists for text, camera and screen.

Long term:

- A user can complete a travel day (text, menu, sign, screen, conversation)
  fully offline with predictable quality and no account.
- NPU/GPU acceleration is selected automatically and safely.
- The documentation lets a new engineer ship a beta without chat history.
