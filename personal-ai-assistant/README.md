# Personal AI Phone Assistant (Android)

An Android app that works as a personal AI assistant: you speak or type a request, it
plans the steps, asks your permission where needed, uses phone features, checks the
result, and tells you what actually happened.

```
UNDERSTAND → PLAN → ASK PERMISSION WHEN REQUIRED → ACT → VERIFY → REPORT
```

This is **Phase 1–3 of the product spec** (basic assistant, phone control, memory and
permissions). Call screening and the AI voice call agent (Phases 4–5) are not built yet;
see [Limits](#limits).

## What it can do

| You say | What happens | Permission level |
|---|---|---|
| "Call Ahmed" / "Call my father" | Finds the contact (including relationships you taught it and words like *nanna*, *amma*, *abba*) and dials | 2 – asks first by default; can be set to automatic |
| "Call the last person who called me" | Reads the call log, then dials | 1 + 2 |
| "Who called me while I was sleeping?" | Reads the call log for that time range | 1 – automatic |
| "Send Ravi a message saying I'll call him tomorrow" | Shows the exact text and recipient, sends the SMS, waits for the network to confirm delivery to the carrier | 3 – always asks |
| "What's on my calendar today?" | Reads calendar events | 1 – automatic |
| "Create a meeting tomorrow at 10" | Adds a calendar event | 3 – always asks |
| "Remind me tomorrow at 10 AM to call Ahmed" | Schedules a notification | 1 – automatic |
| "Open WhatsApp" | Launches the app | 2 – asks first by default |
| "Remember that Ahmed is my business partner" | Saves a memory; "call my business partner" then works | 1 – automatic |
| "Forget memory 4" | Deletes a memory | 4 – always asks, can't be changed |
| "What's the weather?" / "Find the electricity office number" | Web search | 1 – automatic (can be turned off) |

A failed action is always reported as failed. The agent only claims success when the tool
returned success. For example, an SMS counts as sent only after the phone radio confirms it.

## How it's built

```
personal-ai-assistant/
├── core/   Plain Kotlin, no Android. Unit-tested.
│   ├── AssistantAgent     Claude tool-use loop: plan → permission check → run → verify → report
│   ├── Permissions        4 permission levels + confirmation gate
│   ├── Tools              Tool interface: prepare (resolve + preview) then run (act + verify)
│   ├── Contacts           Name / relationship matching (English, Hindi/Urdu, Telugu words)
│   └── SystemPrompt       Assistant instructions + your saved memories
└── app/    Android (Kotlin, Jetpack Compose, Room, WorkManager)
    ├── tools/             Contacts, calls, call log, SMS, apps, reminders, calendar, memory
    ├── voice/             Android speech recognition + text-to-speech
    ├── data/              Room database (memories, activity log), encrypted settings
    └── ui/                Assistant, Memory, Activity and Settings screens
```

- **The AI can only request tools.** It never gets direct access to the phone. Each
  request goes through the permission policy. If confirmation is needed, the app shows
  exactly what will happen (for example, "Text Ravi Kumar (+91…): 'I'll call you
  tomorrow'") before it runs.
- **Model:** Claude Opus 5 by default, with Claude Sonnet 5 available in Settings. The app
  uses adaptive thinking, and effort is set in Settings (default *medium* for quick replies).
  On Opus 5 the app turns on server-side refusal fallbacks, so if a safety classifier
  declines a request, the API retries it on a recommended model.
- **Privacy:** your API key is stored encrypted with the Android Keystore. Memories and
  the activity log stay on the phone and can be deleted from the app. Your request, your
  saved memories and the results of the tools it uses (for example, contact matches or
  calendar entries) are sent to the Claude API.

## Install

1. Download the APK: go to **Actions → Android Assistant → latest run → Artifacts →
   `personal-ai-assistant-debug-apk`**.
2. On your phone, allow installing apps from your browser or file manager, then open the
   APK.
3. Open the app → **Settings**:
   - Paste an Anthropic API key (from console.anthropic.com) and tap **Save**.
   - Tap **Grant phone permissions**.
   - Optionally set your name and a speech language (`en-IN`, `te-IN`, `hi-IN`, …).
4. Go to **Assistant** and tap the mic, or type.

## Build from source

Requirements: JDK 17 and the Android SDK (platform 35).

```bash
cd personal-ai-assistant
./gradlew :core:test          # agent/permission/contact-matching unit tests
./gradlew :app:assembleDebug  # → app/build/outputs/apk/debug/app-debug.apk
```

## Limits

- **The assistant can dial but can't talk on calls.** Android doesn't let regular apps
  hear or inject audio on cellular calls. So "call Ravi and ask whether the shop is open"
  dials Ravi, and you do the talking. The assistant can send an SMS instead. Automatic
  call answering, screening and an AI voice agent (spec Phases 4–5) need a different
  approach: call forwarding to a cloud voice agent, or being the default phone app for
  screening. That is the next phase.
- **WhatsApp:** it can open WhatsApp, but there's no official API to send messages from a
  personal account.
- **Reminders** use WorkManager. Android may deliver them a few minutes late when the
  phone is in deep sleep.
- This is a debug build for personal use. It isn't prepared for the Play Store: Google Play
  restricts apps that request SMS and call-log permissions.
