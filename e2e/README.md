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
| `03-login` | yes | The key is accepted, Matrix credentials derive, first sync completes |
| `01-open-chat` | yes | A room from the list opens |
| `02-send-text` | yes | A message reaches the timeline — **needs a writable chat**; a channel has no composer |

`.env` may use either the documented `MAESTRO_E2E_*` names or the short
`TEST1` / `TEST2` aliases. Values are parsed line by line, never sourced —
a mnemonic phrase contains spaces and would otherwise be run as a command.

## Selectors — what actually works in a Capacitor WebView

Learned the hard way on the first real runs; do not re-derive it:

- **`data-testid` is invisible to Maestro.** DOM nodes in a WebView expose no
  `resource-id`, so `id:` selectors never match. The attributes are still in
  the markup and are useful to DOM tests, but flows cannot use them.
- **A placeholder arrives as `hint`, not `text`,** so `tapOn: "Enter your
  private key"` fails. Address such fields by position instead —
  `tapOn: { below: "Private Key or Mnemonic" }`.
- **List rows concatenate all their text** ("Bastyon_Team Bastyon_Team Aug 14
  Forta Chat is…"), and Maestro matches the regex against the whole string.
  Wrap the name: `".*${MAESTRO_E2E_TARGET_ROOM}.*"`.
- **Assert on "Go back" to prove a room opened.** A channel is read-only and
  renders no composer; a fresh chat renders no messages. The back button is
  the only landmark common to every room.
- The first Matrix sync after `clearState` takes minutes — chat-list waits
  need a timeout in the hundreds of seconds, far longer than login itself.

## Two-device call scenario

`scripts/e2e-call.sh` signs both emulators in and runs the callee and caller
flows in parallel against a live Matrix exchange. It proves signalling, the
native ringing screen, answer and an established call — **not** that anyone can
hear anything: emulator audio is synthetic, and the audio faults users report
are vendor-firmware defects that need real hardware.

```bash
MAESTRO_E2E_PEER_NAME="<name device A sees for device B>" scripts/e2e-call.sh
scripts/e2e-call.sh --skip-login    # devices already signed in
```

Four things this scenario taught us, all encoded in the flows:

- **Keep the screens on.** A headless emulator with a dark screen lets Android
  freeze the app process — logcat says "Sending oneway calls to frozen
  process", the Matrix sync stops and the invite never arrives. It looks
  exactly like the "incoming call never rings" bug. The runner issues
  `svc power stayon true` for this reason.
- **`launchApp` must not restart the app** (`stopApp: false`). Maestro kills
  and relaunches by default, which tears down the Matrix session; the callee is
  then resyncing when the invite lands and never rings.
- **The ringing screen is a native Activity**, so its wording comes from
  `android/app/src/main/res/values/strings.xml` ("Incoming audio call"), not
  the JS locale ("Incoming voice call").
- **"Connected" is on screen for a moment only** before the status line becomes
  a running timer. Assert that "Connecting" disappeared instead — waiting for
  the word itself is a race.
