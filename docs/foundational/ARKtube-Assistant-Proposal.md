# Proposal: A Foreground-Aware Voice Assistant for ARKtube

> **Historical note:** written against the old Neutralino-based shell
> (`app-init.js`, since removed from `main`). The gamepad/remote input
> path it references now lives in
> `arktube_linux/resources/js/user-script.js`; this proposal has not
> been implemented against the native `arktube_linux/` app.

## 1. The problem this solves

ARKtube's stated goal is "YouTube, installed" — you get the real youtube.com/tv interface with native window behavior. It already intercepts remote/gamepad input and re-dispatches it as `ArrowUp/Down/Left/Right`, `Enter`, `Escape`, `Home` so a Fire TV remote (or any controller exposed as a HID gamepad) drives navigation the same way a keyboard would.

That solves *navigation*. It doesn't solve *intent*. Right now, "play the latest video from [channel]" or "play [song]" still means: press Home, navigate to search, hunt-and-peck a query with a D-pad, or — as the original problem in this thread showed — hit a dedicated Assistant button that opens a search box and just sits there waiting for a keyboard that doesn't exist.

Every real smart-TV platform solved this the same way: a mic button that skips navigation entirely and goes straight to intent. Google Assistant on Android TV / Google TV lets you say **"Play [show or movie title] on [streaming service]," "Show me [actor] movies," "Play the latest video from [channel] on Chromecast,"** or **"Play [song]"** and it resolves that directly to an action — no D-pad involved. That's the actual gap between "YouTube in a window" and "a smart TV app."

## 2. Why this fits ARKtube specifically, not just as a bolt-on

ARKtube's own philosophy section says it plainly: *"If the desktop needs something YouTube doesn't provide, add the smallest layer necessary."* Voice intent is exactly that kind of missing layer — YouTube's own UI doesn't provide a "say what you want" affordance for TV remotes at all outside of Google's own Assistant integration, which only exists on licensed Google TV hardware, not on a generic Ubuntu box with a repurposed remote.

It also fits the "controller/remote support" section directly. `app-init.js` already polls the Gamepad API and treats remote button events as first-class input. A mic/assist button is just one more button in that same input path — the only difference is what happens after the press: instead of re-dispatching a key event, it opens a capture window, gets text, and dispatches an *action* instead of a keystroke.

## 3. Proposed mechanism: foreground-scoped interception

**Foreground-only capture, matching the existing remote-interception model:**

- ARKtube already has to know whether its window is focused (needed for the existing key/gamepad re-dispatch to behave correctly and not steal input from other apps). The assistant button rides on that same foreground check: the raw input listener for the assist button is only active while the ARKtube window has focus, exactly like the D-pad interception. When ARKtube isn't in the foreground, the remote's search/assist button falls through to whatever it already does (GNOME search, another app, etc.) — no system-wide grab, no conflict with other software.
- On Linux this means reading the same evdev device your remote already presents (in this case, the "AR Keyboard" device seen in `evtest`, which reports `KEY_SEARCH`, `KEY_ASSISTANT`, and `KEY_VOICECOMMAND` codes) and only forwarding a press to the app when the window manager reports ARKtube as active. No `udev` rule needs to change system-wide behavior — the app just listens and decides whether to act.

## 4. Proposed pipeline: press → speech → intent → action

```
[Remote assist button, ARKtube in foreground]
        │
        ▼
  Start mic capture (press-and-hold or press-to-toggle)
        │
        ▼
  Local STT  (whisper.cpp, tiny/base.en model)
        │  text: "play the arknet latest video"
        ▼
  Local small LLM, function-calling mode
        │  → { "action": "play_channel_latest", "channel": "arknet" }
        ▼
  Action executor (talks to youtube.com/tv already loaded in the webview)
        │
        ▼
  Result: video starts playing, same as if navigated by hand
```

### Why this is genuinely small, not a research project

- **Speech-to-text:** `whisper.cpp` runs the `tiny`/`base` Whisper models fully offline, in C++, with no GPU requirement. Benchmarks show roughly 4–10x speedup over Python Whisper on CPU alone, and the tiny model footprint is small enough to run comfortably on a Raspberry Pi 4, let alone a desktop PC. For a single held-button utterance (a few seconds, not a live open mic), this is comfortably real-time.
- **Intent parsing:** this does not need a general chatbot. It needs a small model that maps a transcribed sentence to one of a fixed, known set of actions with typed arguments — precisely the "function calling" pattern. This is a solved, well-trodden shape:
 - `local-llm-function-calling` demonstrates exactly this: you define a JSON schema of functions (e.g. `play_channel_latest(channel)`, `play_song(query)`, `open_video(query)`, `pause()`, `search(query)`), and the generator constrains a local model's output to match that schema.
 - `home-llm` (Home Assistant's local LLM integration) does the same thing for smart-home control: small fine-tuned models at **1B and 3B parameters**, quantized, specifically trained to map natural language to structured function calls, designed to run on low-resource hardware like a Raspberry Pi.
 - `ada_local` is a full open-source example of this exact stack already wired together end-to-end: wake word → Whisper STT → a small "router model" (~500MB) for intent classification → action execution → optional TTS response — built by one person as a Jarvis-style assistant.

 None of these projects need a datacenter. A 1–3B parameter model quantized to 4-bit is small enough to sit resident in RAM on a modest desktop and respond in well under a second for a short command, especially since the output space is constrained to a known function schema rather than open-ended generation.

- **Action execution:** ARKtube already loads youtube.com/tv in its own webview and injects `app-init.js` into the page. The action executor is just more of the same pattern already established in the codebase — dispatching synthetic events/URL navigation into the loaded page, or calling the YouTube Data API directly for a subset of actions (see below) and then navigating the webview to the result.

## 5. Mapping to a concrete command set

Google Assistant's TV command surface (researched separately) breaks down into a handful of categories. Each maps to a small, enumerable function:

| Google Assistant TV command | ARKtube function | Notes |
|---|---|---|
| "Play [show/song] on [app]" | `play_query(text)` | Search YouTube for the top result and load it |
| "Play the latest video from [channel]" | `play_channel_latest(channel)` | **Directly implementable via YouTube Data API**: `channels.list` → `contentDetails.relatedPlaylists.uploads` → `playlistItems.list?maxResults=1` gives the newest video's ID, then navigate the webview to `youtube.com/watch?v=<id>` |
| "Show me [actor] movies" / "Find [genre]" | `search(query)` | Straight text search, same as typing into the YouTube search box |
| "Pause" / "Stop" / "Resume" | `pause()` / `stop()` / `resume()` | Dispatches the same keyboard events YouTube's own player already listens for |
| "Open YouTube" / "Go home" | `go_home()` | Navigate to `youtube.com/tv#/` |
| "Louder" / "Volume up" / "Set volume to [n]" | `volume(delta_or_value)` | Controls the `<video>` element directly |

The "latest video from a channel" case is worth calling out because it's the one the user specifically asked about, and it's cleanly solvable without any AI at all once the *intent* is correctly parsed — the hard part is turning "play arknet's latest video" into `{channel: "arknet"}`, not fetching the video once you know that.

## 6. Why local, not cloud

- Keeps ARKtube's stated design principle of staying a **thin layer** — no account, no subscription, no dependency on a company's Assistant backend that could be discontinued (as Google Assistant itself is being deprecated in favor of Gemini, per Google's own March 2025 announcement).
- No network round-trip for the STT/intent steps means the "press button, thing happens" latency stays TV-like rather than web-request-like.
- Matches the privacy expectations of a self-hosted YouTube shell: nothing about what you say to your TV needs to leave the machine.

## 7. Open questions / risks

- **Push-to-talk vs. wake word:** press-and-hold on the remote's assist button is simpler and cheaper than always-on wake-word detection, and matches how the Fire TV remote's own Alexa button already behaves (hold to talk). Recommend starting here rather than open-mic listening.
- **Model choice tradeoff:** a 1B function-calling model will be fast but will occasionally misparse channel names or song titles with unusual spelling; a 3B model is more accurate but heavier. This is worth benchmarking on the actual target hardware before committing.
- **Ambiguous channel/song matches:** "play arknet's latest video" needs a channel-name → channel-ID resolution step (fuzzy match against subscriptions or a search fallback) before the Data API calls above will work — this is the one piece that isn't a solved off-the-shelf component and would need custom logic.
- **Scope creep:** Google Assistant's TV surface also includes smart-home control, general knowledge questions, etc. Recommend explicitly scoping v1 to YouTube-native actions only (play/pause/search/channel-latest/volume) rather than trying to rebuild all of Assistant.

## 8. Summary

ARKtube already treats the remote as a first-class, foreground-scoped input device. Extending that same pattern from "button → keystroke" to "button → speech → structured intent → action" is a natural, incremental next step, not a new architecture. The components needed (whisper.cpp for STT, a small local function-calling model for intent, the YouTube Data API for content resolution) are all small, offline-capable, and already proven in comparable open-source projects. This is the single feature that would take ARKtube from "YouTube in a window" to "behaves like a smart TV."
