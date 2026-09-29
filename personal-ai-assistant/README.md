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
| "Call Ahmed" / "Call my father" | Finds the contact (including relationships you taught it and words like *nanna*, *amma*, *abba*) and dials | 2 – asks first by default; can be set to automatic. Numbers not in your contacts always ask. |
| "Call the last person who called me" | Reads the call log, then dials | 1 + 2 |
| "Who called me while I was sleeping?" | Reads the call log for that time range | 1 – automatic |
| "Send Ravi a message saying I'll call him tomorrow" | Shows the exact text and recipient, sends the SMS, waits for the network to confirm delivery to the carrier | 3 – always asks |
| "What's on my calendar today?" | Reads calendar events | 1 – automatic |
| "Create a meeting tomorrow at 10" | Adds a calendar event | 3 – always asks |
| "Remind me tomorrow at 10 AM to call Ahmed" | Schedules a notification | 1 – automatic |
| "What reminders do I have?" / "Cancel the Ahmed reminder" | Lists saved reminders / cancels one | 1 / 2 – asks first by default |
| "Move my 4 PM meeting to 5 PM" | Changes a calendar event (not repeating events) | 3 – always asks |
| "Delete tomorrow's dentist appointment" | Deletes a calendar event | 4 – always asks |
| "Ahmed is actually my cousin, update that" | Corrects a saved memory (also editable on the Memory screen) | 1 – automatic |
| "Open WhatsApp" | Launches the app | 2 – asks first by default |
| "Remember that Ahmed is my business partner" | Saves a memory; "call my business partner" then works | 1 – automatic |
| "Forget memory 4" | Deletes a memory | 4 – always asks, can't be changed |
| "What's the weather?" / "Find the electricity office number" | Web search | 1 – automatic (can be turned off) |

A failed action is always reported as failed. The agent only claims success when the tool
returned success. For example, an SMS counts as sent only after the phone radio confirms it.

## Conversation history

The chat is saved on the phone and comes back after the app restarts, including for the
AI. The AI sees the last 20 exchanges; older ones stay on screen but are left out of its
context so long chats keep working. **+** starts a fresh conversation and deletes the
saved one.

## Call screening (Android 10+)

Open the **Calls** tab and tap **Turn on call screening**. Android asks you to make
Personal AI your "caller ID & spam" app. From then on, while an incoming call rings, the
app looks up the number in your contacts, your remembered relationships and your caller
categories, and applies the first matching rule:

| Default rule | Action |
|---|---|
| Blocked numbers | Reject + notify |
| Spam | Silence + notify |
| Important callers | Ring + notify |
| Contacts | Ring |
| Hidden numbers | Ring + notify |
| Unknown callers | Ring + notify |

Rules can be edited, turned off or added (IF condition [AND/OR condition] THEN ring /
silence / reject, optionally notify). Every screened call is listed under **Calls →
History** with the caller, category, decision, time and reason. The assistant can also
read that history and set categories ("mark 98765 43210 as spam"), always after you
confirm.

Limits: screening can only let a call ring, silence it or reject it. It does not answer
calls, record them, or talk to callers. Android usually doesn't pass calls from saved
contacts to a screening app, so those ring normally without being screened.

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
- **AI service:** choose in Settings.

  | Service | Cost | Notes |
  |---|---|---|
  | Claude (Anthropic) | Paid, from $5 | Default model Claude Opus 5, with Claude Sonnet 5 as the cheaper option. Uses adaptive thinking with adjustable effort, web search, and server-side refusal fallbacks on Opus 5. |
  | Gemini (Google) | Free tier | Uses Gemini's OpenAI-compatible endpoint. On the free tier, Google may use requests to improve its products. |
  | Groq | Free tier | Fast open models. Less reliable on multi-step requests. |

  Gemini and Groq don't get web search in this app. Their model names change often, so
  **Check key & list models** fetches the models your key can use and lets you pick one.
  Switching service starts a new conversation.
- **Privacy and security:**
  - API keys are stored encrypted with the Android Keystore.
  - The local database (memories, chat, activity log, reminders, call screening) is
    encrypted with SQLCipher. Its random key is protected by the Android Keystore. An
    existing unencrypted database is converted on first launch; if that ever fails, the
    app keeps working unencrypted and says so under Settings → Security.
  - Optional app lock (Settings → Security) asks for your fingerprint, face or screen lock
    when opening the app, and again after 30 seconds in the background. While it's on,
    call and reminder notifications hide their details on the lock screen.
  - Settings → Security → **Delete all my data** removes memories, chat, activity,
    reminders and call-screening data (API keys and settings are kept).
  - Your request, your saved memories and the results of the tools it uses (for example,
    contact matches or calendar entries) are sent to the AI service you selected. Memories and
  the activity log stay on the phone and can be deleted from the app. Your request, your
  saved memories and the results of the tools it uses (for example, contact matches or
  calendar entries) are sent to the Claude API.

## Install

1. Download the APK: go to **Actions → Android Assistant → latest run → Artifacts →
   `personal-ai-assistant-debug-apk`**.
2. On your phone, allow installing apps from your browser or file manager, then open the
   APK.
3. Open the app → **Settings**:
   - Pick an AI service and paste its API key: console.anthropic.com (Claude),
     aistudio.google.com/apikey (Gemini, free) or console.groq.com/keys (Groq, free).
   - For Gemini or Groq, tap **Check key & list models** and pick a model.
   - Tap **Save**.
   - Tap **Grant phone permissions**.
   - Optionally set your name and a speech language (`en-IN`, `te-IN`, `hi-IN`, …).
4. Go to **Assistant** and tap the mic, or type.

## Permanent signing key

Release APKs are signed with a permanent key so updates install over the old version and
keep your data. The key is stored only in GitHub's encrypted secrets
(`SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`,
`SIGNING_KEY_PASSWORD`), never in the repository. With the secrets set, CI also uploads
`personal-ai-assistant-release-apk`; install that one. Switching from the old debug build
to the first signed release needs one last uninstall.

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
  phone is in deep sleep. Reminders created before version 0.3.0 aren't listed.
- **Repeating calendar events** can't be changed or deleted by the assistant.
- This is a debug build for personal use. It isn't prepared for the Play Store: Google Play
  restricts apps that request SMS and call-log permissions.
