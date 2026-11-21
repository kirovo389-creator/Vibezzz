# Nova Chat

A native Android AI chat client with a minimal dark interface inspired by modern conversational apps.

## Features
- Bring your own API key, Base URL, and Model ID.
- OpenAI-compatible `/v1/chat/completions` streaming API.
- Streaming responses and optional reasoning display (`<think>...</think>`, `reasoning_content`, or `reasoning` fields).
- API credentials encrypted locally with Android Keystore-backed encrypted preferences.
- Dark Compose interface, chat drawer, and animated reasoning disclosure.

## Setup
1. Install the APK from the latest GitHub Release.
2. Open Settings and enter your API key, Base URL, and Model ID.
3. Save and start a chat.

For an OpenAI-compatible service, Base URL is usually the service's `/v1` endpoint, for example `https://api.example.com/v1`.

## Build
Requires JDK 17 and Android SDK 35. The GitHub Actions workflow builds the APK on pushes to `main` and publishes it as a GitHub Release.

## Notes
- The app sends chat content and the configured API key to the Base URL you provide.
- API credentials are stored encrypted on the device; they are not committed to this repository.
- The reasoning panel only displays reasoning that the selected provider/model actually returns. It cannot reveal hidden internal reasoning that an API does not expose.
- This project uses an original interface inspired by common AI chat patterns; it does not include proprietary Claude assets or source code.
