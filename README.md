# AIChatExporter

An Android app that turns a shared AI-chat link into a document you can keep.
Paste a public share link, the app reads the conversation, cleans it up, and
exports it as Word, PDF, PowerPoint, HTML, Markdown, plain text, or JSON.

## What it reads

- ChatGPT share links (`chatgpt.com`, `chat.openai.com`)
- Claude share links (`claude.ai`)
- Gemini share links (`gemini.google.com`)

The app fetches the shared page directly and needs no account. An optional
reader key can help when a site refuses direct requests.

## Features

- Paragraph-level text direction: Persian runs right-to-left and English runs
  left-to-right inside the same document, in every export format
- Export studio with per-format options
- Local library of conversations and exports (Room), plus optional Google Drive backup
- Daily automatic backup, and a restore that never overwrites newer local data
- Background export, so long documents do not block the screen

## Build

Requirements: JDK 17, Android SDK 36, and a device or emulator running
Android 10 (API 29) or newer.

```bash
cp .env.example .env      # then fill in the values
./gradlew :app:assembleDebug
```

`.env` holds:

- `GEMINI_API_KEY` - optional; importing links works without it
- `GOOGLE_CLIENT_ID` - the Web client ID of a Google Cloud project whose Android
  OAuth client is registered for this package name and signing SHA-1

Release builds are signed from three environment variables: `KEYSTORE_PATH`,
`STORE_PASSWORD`, and `KEY_PASSWORD`. Add `-PpublicBuild=true` to leave the AI
key out of the artifact.

## Layout

- `app/src/main/java/com/example/domain/parser` - link extraction and the
  ChatGPT stream decoder
- `app/src/main/java/com/example/domain/bidi` - paragraph direction rules shared
  by every writer
- `app/src/main/java/com/example/domain/exporter` - PDF, OOXML, HTML, and JSON writers
- `app/src/main/java/com/example/data` - Room storage, Drive backup, Gemini client
- `app/src/main/java/com/example/ui` - Compose screens
- `contracts/` - behavior the app and the export writers agree on
- `docs/privacy-policy.md` - the privacy policy

## Privacy

Conversations stay on the device. Reading a share link fetches only that page.
Cloud backup is opt-in and uses the `drive.file` scope, which limits the app to
files it created. See `docs/privacy-policy.md`.
