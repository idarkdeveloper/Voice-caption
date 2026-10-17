# Voice-caption

Android app that shows live, translated captions for any film playing on the phone.

## Privacy notes

- The free tier processes audio on the device: speech recognition (Whisper) and translation (ML Kit) run locally. Audio is never recorded or stored, and no audio or transcript is sent anywhere.
- The `INTERNET` permission is used only to download the speech model (from Hugging Face) and the ML Kit language packs.
- ML Kit's bundled APIs may send anonymous performance and usage metrics to Google. No audio or text is included, but this is network traffic beyond the downloads above.
- Before any store listing, a real privacy policy must describe the above (including the ML Kit metrics). Nothing here is a statement of legal compliance.
- A cloud tier is planned as a later, separate component; it is not part of the free tier and would need its own privacy review.
