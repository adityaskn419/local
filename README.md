# Personal phone remote control scaffold

Two pieces:

- `server/` — a small relay you deploy once on any cheap VM (or a free-tier host). It sits on the
  internet so your laptop's dashboard and your phone's agent can find each other without either
  side needing a public IP or port-forwarding.
- `android-agent/` — an Android Studio project. Installs like any normal app, shows up in
  Settings > Apps, and can be uninstalled normally. It runs a foreground service (persistent
  notification, by Android design) that reconnects to your relay and executes commands.

## 1. Deploy the relay

```
cd server
npm install
RELAY_TOKEN="pick-a-long-random-string" PORT=8080 npm start
```

Deploy this to any VM/PaaS (a $4/mo VPS, Render, Railway, Fly.io all work). Put it behind HTTPS
(e.g. Caddy/nginx or the platform's built-in TLS) so the WebSocket runs over `wss://`.

Open `https://your-host/` for the dashboard — paste the same token, hit Connect.

## 2. Point the app at a stable config endpoint (no rebuild needed later)

The app doesn't hardcode a relay URL. Instead it fetches `config/relay-config.json` from your own
GitHub repo (a raw file URL — permanent, never changes) every time it (re)connects, and caches
whatever it last got. So when you switch tunnel providers (Cloudflare → ngrok → your own VM →
Tailscale) later, you just edit that one JSON file and push — the already-installed app on the
phone picks it up on its next reconnect, with zero rebuild/reinstall.

1. Push this repo to GitHub (public repo is simplest — raw files are fetchable with no auth).
2. Edit `android-agent/app/src/main/java/com/devtools/remoteagent/ConfigFetcher.kt` and set
   `CONFIG_ENDPOINT` to your actual raw URL, e.g.:
   `https://raw.githubusercontent.com/<you>/<repo>/main/config/relay-config.json`
   (this one line is the only thing baked into the APK — the JSON content behind it is what you
   keep updating)
3. Whenever your relay's actual address changes, edit `config/relay-config.json` and push. That's it.

## 3. Build the APK via GitHub Actions (no Android Studio needed)

`.github/workflows/build-agent.yml` builds a debug APK on every push to `android-agent/**`, or
on-demand via the Actions tab ("Run workflow"). After a run finishes:

- Go to the repo's **Actions** tab → the latest run → download the `remote-agent-debug-apk`
  artifact (a zip containing `app-debug.apk`).
- Transfer it to your phone (email to yourself, Google Drive, `adb install`, etc.) and install
  (enable "install unknown apps" for whichever app you use to open it).
- Tap **Exempt from battery optimization** on first launch and allow it — otherwise Android's Doze
  will eventually suspend the background connection (a real Settings toggle, same as apps like
  WhatsApp need).

You should not need to open the app again after this — it reads its config from the endpoint
above on every reconnect.

Once running, the notification stays visible (required by Android for any foreground service —
there's no way to hide it, by design) and the app reconnects automatically on network changes and
after reboot.

## 3. Control it

From the dashboard, pick the device id and send commands: volume, battery status, launch/kill app,
Wi-Fi toggle. The agent only uses regular Android app APIs — no root — so a few actions are
intentionally restricted by the OS itself, not by this code:

- **Wi-Fi enable/disable** is blocked for regular (non-device-owner) apps on Android 10+.
- **Force-stopping another app** isn't available to a normal app; only its own background
  processes can be killed via `killBackgroundProcesses`.

## 4. For full shell parity (adb-level control) without root

If you need things beyond the Android API surface — arbitrary `settings put`, `svc wifi disable`,
real `am force-stop`, screen mirroring — pair this with wireless ADB instead of reinventing it:

1. On the phone: Developer Options > Wireless debugging > pair once.
2. Get a persistent tunnel from the phone's ADB port to your VM. The simplest approach: install
   Termux on the phone, run an autossh reverse tunnel to your VM (`ssh -R 5555:localhost:5555 you@vm`),
   or use Tailscale/WireGuard between the phone and the VM so `adb connect <phone-tailscale-ip>:5555`
   works from anywhere.
3. From your laptop, SSH to the VM (or connect to the same WireGuard network) and run
   `adb connect ...` then any `adb shell` command directly.

This gets you the full command surface you described, still fully visible and removable by you
(Termux, the WireGuard config, and wireless debugging are all normal, user-controlled toggles).

## What this intentionally does NOT do

No anti-uninstall, hidden-from-launcher, or un-killable-by-owner behavior is built in here. That
class of persistence is what turns a personal test tool into stalkerware-shaped software, and it's
also just bad for you operationally — you want to be able to wipe/reset/uninstall this yourself
without a rescue path.
