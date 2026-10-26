# Device QA Matrix

## Minimum matrix

| Class | Example | What to cover |
|---|---|---|
| Reference flagship | OnePlus 13 / Android 16 | screen shortcut, MediaProjection FGS, camera crop, TTS |
| Pixel-like | Pixel 8/9 / Android 15-16 | gesture nav, TalkBack, camera HAL, projection |
| Samsung | One S24/S25 / One UI | accessibility shortcut, multi-window, camera lenses |
| Older device | API 26-28 arm64 | install, storage export, no modern APIs crash |
| Tablet / large screen | Android 14+ | overlay scaling, camera preview, insets |

No device is currently attached to the build workstation; beta.10 is
**device-pending**.

## Scenario checklist

### Install and startup

- [ ] Install the signed beta over the previous beta; version code increases.
- [ ] Cold start, onboarding, no crash.
- [ ] About shows the actual version and Android API.

### Text

- [ ] Detect source automatically; switch target; copy and TTS.
- [ ] Empty input, long text, CJK and RTL.
- [ ] History persists after restart.

### Camera

- [ ] Live camera shows inline translation, not an empty preview.
- [ ] Shutter captures and paints translation.
- [ ] Gallery: English, Japanese/Chinese and mixed image.
- [ ] Two rapid gallery picks: second result wins, no old paragraph sheet.
- [ ] 0.6/1/3x zoom, flash, front/back if available.
- [ ] Rotate and return; camera rebinds.
- [ ] Low light / small text warning is visible but not covering controls.

### Screen translation

- [ ] Accessibility shortcut works repeatedly (10 presses, close between).
- [ ] Floating bubble translates, closes overlay, remains movable and reusable.
- [ ] Overlay fits source blocks; no translated line overlaps another.
- [ ] Rotate/window change dismisses stale overlay.
- [ ] Save produces the rendered overlay, not the raw screen.
- [ ] FAST_ONLY uses accessibility only; Full Scan picks up raster/grey text.
- [ ] Android 14+ projection consent and foreground notification.

### Voice

- [ ] Mic permission, start/stop, no orphan recording.
- [ ] TTS timeout and stop; AEC during speech.
- [ ] Long CJK utterance, at least 5 turns.
- [ ] Recording file finalizes and plays after restart.

### Privacy/accessibility

- [ ] TalkBack can reach the floating bubble and overlay close/actions.
- [ ] Reduced motion disables scan/reveal animations.
- [ ] No key filtering; no unexpected network requests during translation.
- [ ] Backup disabled; exported files are only in expected folders.

## Log capture

When reporting a failure, include:

```bash
adb logcat -d -v threadtime > parlex-logcat.txt
adb shell dumpsys package com.translive.app | grep -E "version|firstInstall"
```

Remove user text, file paths, account names and personal images before sharing.
Use tags `CameraVM`, `ScreenOverlayService`, `ScreenA11yService`,
`ArTranslateOverlayView`, `SpeechEngine`, `SystemTtsEngine`.
