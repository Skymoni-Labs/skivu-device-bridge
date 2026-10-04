# Phone Use

**English** | [简体中文](README.zh-CN.md)

Give AI agents a way to see and interact with an Android phone—with approval on the device.

Phone Use runs an observation and control service on Android 11+. It exposes accessibility trees, screenshots, and device actions through a JSON API, MCP, and an installable agent Skill. The phone service needs no root, ADB, or model backend. An AI client runs separately and connects over your local network.

## What you can do

- **Observe the screen:** compact UI trees, JPEG screenshots, full trees on demand, and explicit waits for an app or element.
- **Interact with apps:** tap, long-press, swipe, scroll, replace or insert text, launch apps, and use Back, Home, or Recents.
- **Connect your preferred client:** use the built-in web console, Python CLI, MCP bridge, or `phone-use` Skill.
- **Use multiple devices:** route every call by device ID; different devices can run independently.
- **Keep control on the phone:** approve each client locally and revoke access at any time.

The app supports English and Simplified Chinese, with a system-language option. The web console follows the browser language and falls back to English.

## Quick start

### 1. Build and install

You need **JDK 25** and an Android SDK installation with **API 37**. The repository pins Gradle **9.7.1** and Android Gradle Plugin **9.4.0**. Configure your SDK in Android Studio or an untracked `local.properties` file.

Run `git submodule update --init --recursive` before building to obtain the bundled Skill. The build packages the current client, API references, and Skill instructions into the APK automatically.

```sh
# macOS / Linux
./gradlew :app:assembleDebug
```

```powershell
# Windows PowerShell
.\gradlew.bat :app:assembleDebug
```

Install `app/build/outputs/apk/debug/app-debug.apk` on Android 11 or later. See [development and validation](docs/validation.md) for checks and emulator tests.

### 2. Start the phone service

1. Open Phone Use and enable its accessibility service in Android settings. Some devices require allowing restricted settings for a sideloaded app first.
2. The service starts automatically on port 8443 when you open the app. Allow notifications, and on Android 17+, grant local network permission when requested.
3. Keep the phone awake and unlocked for input. Connect your client machine to a network that can reach the IPv4 address shown in the app.

On Xiaomi/Redmi/POCO, Phone Use checks the background pop-up permission when the screen resumes. The dashboard shows **Background app launches** when the permission is denied or a launch was not confirmed. If the firmware does not support checking, the dashboard only shows a reminder after an unconfirmed launch. **Settings → Background app launches** always provides a manual entry to system permissions; permission changes must be made there. On every brand, a denied or unconfirmed launch also posts a separate notification that opens Phone Use. Repeated failures update one notification; a confirmed launch or dismissing the in-app reminder clears it.

**Settings → Background keep-alive** is enabled by default and can be turned off. It includes battery optimization, autostart, and notification guidance. Allow background activity and lock the recent task where supported to reduce accessibility interruptions. Force-stop still requires reopening the app, and revoked accessibility access needs manual re-enabling; see [Usage](docs/usage.md).

### 3. Pair a client

Connect with the Python client or MCP bridge using `https://PHONE_IPV4:8443`. Compare all eight digits with the phone, approve there, and confirm the match in the client. No certificate installation or long fingerprint copy is needed. Direct browser access requires separate certificate trust.

Pairing persists across client and app restarts until revoked or its stored credentials are removed. The browser and MCP bridge pair separately by default. Accessibility is required for device interaction; pairing and status queries can work without it.

**Transport:** port 8443 uses HTTPS with per-device public-key binding. First pairing requires an eight-digit comparison and confirmation on both sides; subsequent connections verify the saved key automatically. Old HTTP credentials require new pairing. See [connection details](docs/usage.md). Only local access through `127.0.0.1` allows HTTP without the insecure connection setting; other HTTP requires that default-off setting. An empty access address allowlist accepts only the phone’s receiving IP; a configured list accepts only listed IPs/domains, including for the local console. Pairing and authorization remain required.

## Connect an AI agent

The recommended route is **Copy AI setup link** in the phone app. Give the link to your AI and ask it to connect. It reads `/connect`, downloads the complete Skill from the same port, verifies and installs it, then starts pairing. Compare all eight digits, approve on the phone, and explicitly confirm the match to the AI. Only **Python 3.10+** is needed; no npx, Node.js, Git, or Internet download. The Skill updates with the app.

The AI host must run programs and reach the phone's address; a cloud-only chat cannot directly reach a phone on your LAN. GitHub installation remains available with **Node.js 22.20+**, **Git**, and **Python 3.10+**:

### Agent Skill

```sh
npx skills add nidhoggfgg/phone-use-skills --skill phone-use
```

Select your AI client and installation scope. Then ask it:

> Use $phone-use to connect to https://192.168.1.20:8443. Show me the verification code, wait for my approval on the phone, then inspect the current screen.

### MCP

Configure your AI client to start this stdio server:

```sh
npx -y --package=github:nidhoggfgg/PhoneUse phone-use
```

Generate a configuration snippet with `phone-use print-config`, or `phone-use print-config --host codex`, using the same `npx` prefix. The bridge handles pairing and stored credentials without exposing tokens to the model.

The [AI integration guide](docs/integrations.md) includes full commands, Windows setup, first connection, updates, and offline packages. GitHub installation requires access to this repository; the package name does not imply an npm registry release.

## Python CLI

The CLI uses Python 3.10+ and the standard library only. From a repository checkout:

```sh
python tools/phoneuse_client.py pair --url https://192.168.1.20:8443 --name laptop
python tools/phoneuse_client.py list-devices
python tools/phoneuse_client.py call get_capabilities --device-id DEVICE_ID
python tools/phoneuse_client.py call observe --device-id DEVICE_ID --screenshot-out screenshot.jpg
```

Replace `DEVICE_ID` with the ID returned by pairing. Credentials are managed in `~/.phoneuse.json`; do not commit or distribute that file. See the [usage guide](docs/usage.md) for actions, JSON arguments, reconnection, and batches.

## Execution model and limitations

Actions execute once. `launch_app` checks for the target foreground package for up to five seconds; an unconfirmed launch returns an unknown result with troubleshooting guidance. Other waits, observations, and business-result verification are explicit client decisions. Every input action needs a unique `request_id`; after a network error, query the original request instead of replaying it under a new ID.

An `executed` result means Android accepted the action or completed the gesture—it does not prove the app task succeeded. Trees and screenshots are collected separately, and protected windows, custom controls, keyboards, and vendor background policies can limit what is available. Physical-device coverage remains incomplete; see the [validation guide](docs/validation.md).

## Documentation

| Guide | Contents |
| --- | --- |
| [Usage and connections](docs/usage.md) | Web console, CLI, pairing, language settings, troubleshooting |
| [AI integrations](docs/integrations.md) | Skill and MCP installation, configuration, updates, offline distribution |
| [API reference](docs/api.md) | Tools, parameters, observation semantics, errors, request retention |
| [Development and validation](docs/validation.md) | Build checks, emulator fixtures, physical-device acceptance |
| [Contributing](CONTRIBUTING.md) | Changes, bug reports, documentation conventions |
| [Security policy](SECURITY.md) | Supported versions and private vulnerability reporting |

The guides above are available in English and Simplified Chinese.

## License

Licensed under the [MIT License](LICENSE).
