# Privacy Policy

Parlex is an offline-first Android translator. This document describes the
data the app handles and the network connections it can make.

## Local processing

Translation, OCR, speech recognition, text-to-speech and local LLM inference run
on the device whenever the selected model is available. Parlex does not upload
user text, screenshots, camera frames, microphone audio or translated text to a
Parlex server. There is no telemetry or analytics SDK.

## Network connections

The app may use the network only for:

- downloading model files and offline packages from their upstream hosts
  (Google ML Kit CDN, Hugging Face, LiteRT model hosts);
- optional currency-rate synchronisation from public exchange-rate endpoints
  configured in the source;
- opening links the user explicitly taps (documentation, releases, support).

No user text or audio is sent with these requests. The exact hosts and model
sources are listed in `THIRD_PARTY_NOTICES.md` and the model catalog.

## Data stored on the device

The app may store translation history, dictionary entries, dialogue sessions,
audio recordings, downloaded models and settings in app-private storage.
`android:allowBackup` is disabled; the data is not included in cloud backup by
default. Users can delete history/audio from the app or clear app storage.
Exported screenshots, documents, text and `.parlex-fast` packages are written
only when the user explicitly exports them.

## Permissions

- **Camera**: camera preview, photo capture and OCR.
- **Microphone**: voice dialogue and speech recognition.
- **Display over other apps**: screen-translation overlay and floating bubble.
- **Accessibility service** (optional): reading on-screen text and taking a
  silent screenshot for screen translation. It is user-enabled in Android
  settings and can be disabled at any time.
- **Notifications / foreground service**: ongoing capture and translation
  status.
- **Internet / network state**: model and optional currency downloads.

Parlex does not request contacts, SMS, call logs, location or broad storage
access.

## Children

Parlex is a general-purpose translation tool and does not knowingly collect
personal information from children.

## Changes

Material changes will be recorded in the repository and release notes.

## Contact

Use the repository's Security tab for a private report, or open a normal issue
for non-sensitive privacy questions:

https://github.com/RandoTeam/Parlex

This document describes the current implementation; it is not legal advice.
