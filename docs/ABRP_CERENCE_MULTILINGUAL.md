# ABRP Cerence multilingual voice selection

## What changed

The BYD Cerence bridge no longer exposes only the English `en` / Zoe voice.

At runtime it now:

1. Calls Cerence `ArkAssistant.getAvailableTtsVoices(...)` against the Telenav TTS data installed in the car.
2. Converts each Cerence language tag to an Android locale (for example `dan-DNK` -> `da-DK`, `deu-DEU` -> `de-DE`, `eng-USA` -> `en-US`).
3. Exposes every discovered voice through Android `TextToSpeech.getVoices()`, which is what ABRP/react-native-tts uses for its Navigation Voice list.
4. Keeps the first/default English voice under the existing `en` alias so the current fresh-install ABRP state remains compatible.
5. Applies a changed voice immediately with `ArkFramework.setTtsVoiceId(...)`.

If multiple Cerence voices use the same locale, the first uses the normal locale alias and additional voices use `-2`, `-3`, etc. (for example `da-DK`, `da-DK-2`).

## Suggested test

- Patch ABRP 7.1.7 with **BYD native Cerence TTS** enabled.
- Open ABRP navigation/voice settings and select **BYD Cerence**.
- Open **Navigation Voice** and confirm multiple installed locales are shown.
- On the Danish vehicle, look for `da-DK` (or another Danish entry if the firmware reports a different locale).
- Select it and use ABRP's voice preview, then run a short route to confirm guidance uses the selected Cerence voice.
- Switch back to `en` and confirm English still works.

The actual list is intentionally not hard-coded: different BYD firmware/regions can carry different TTS voice packs, so the car's Cerence inventory is the source of truth.
