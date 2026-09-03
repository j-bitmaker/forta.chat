# Android E2E (Maestro)

Local regression run against a debug APK on an emulator or a real device. It
covers what unit tests cannot: that the WebView actually boots, the chat opens
and a message leaves the input.

## Setup

1. Maestro: `curl -Ls https://get.maestro.mobile.dev | bash`
2. An emulator (`emulator -avd callA`) or a device with USB debugging.
3. For anything past the smoke flow: `cp .env.example .env` and fill it in.
   `.env` is gitignored and holds a private key — use a throwaway test account,
   never a production one.

## Running

```bash
scripts/e2e-android.sh              # smoke — no credentials needed
scripts/e2e-android.sh --all        # every flow
scripts/e2e-android.sh --build      # rebuild and reinstall the APK first
```

## Flows

| Flow | Needs credentials | What it proves |
|------|-------------------|----------------|
| `00-smoke-launch` | no | The app boots to the sign-in screen — catches a blank WebView |
| `01-open-chat` | yes | Login completes and a chat opens |
| `02-send-text` | yes | A message reaches the timeline |

## Selectors

Flows address elements by `data-testid`, which is why a few components carry
one: `private-key-input`, `message-input`, `send-button`, `chat-header`. Keep
them when refactoring — text selectors break on every locale change.

## Not covered

Calls. A call needs a second participant, so the flows here cannot drive one;
call regressions are covered by the Kotlin and Vitest suites plus manual runs
on real OEM devices.
