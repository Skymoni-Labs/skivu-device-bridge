# Usage and connections

**English** | [简体中文](usage.zh-CN.md)

## Web console

Open the address shown on the phone, such as `http://192.168.1.20:8443/`. Request pairing, compare the codes, and approve on the phone. Search the API catalog, use the parameter form or JSON editor, and inspect the response. Screenshots appear only after an explicit observation request.

The browser stores authorization in an HttpOnly cookie. Closing a tab, restarting the browser, or restarting the app does not normally require pairing again. Revoke access on the phone or use the console's unpair action. Clearing cookies or changing the browser address creates a new pairing context. Long-term tokens are not displayed or exported by the console.

For actions, retain the same `request_id` when querying or retrying the same request. Generate a new ID only for a new action. Connection recovery does not replay inputs.

## Python client

Python 3.10+ is required; no third-party libraries are needed. Run from the repository root:

```sh
python tools/phoneuse_client.py pair --url http://192.168.1.20:8443 --name laptop
python tools/phoneuse_client.py list-devices
python tools/phoneuse_client.py call observe --device-id DEVICE_ID --screenshot-out screenshot.jpg
python tools/phoneuse_client.py call observe --device-id DEVICE_ID --full-tree
```

`pair` displays a verification code and waits for approval. Completed pairings are reused; pending requests are saved so the client can resume claiming them. Pending requests expire after two minutes and are cleared by a phone-service restart.

The default registry is `~/.phoneuse.json`. Use `--config /absolute/path/registry.json` **before the subcommand** for a different location. Always pass the returned `DEVICE_ID`, including when only one device is connected. Update an address with `pair --url NEW_URL --device-id DEVICE_ID`. The bridge checks installation identity before sending stored credentials; an old configuration without a trusted device ID requires new approval.

### JSON arguments and actions

For portable quoting, save arguments as a UTF-8 file named `action.json`:

```json
{"request_id":"home-001"}
```

```sh
python tools/phoneuse_client.py call home @action.json --device-id DEVICE_ID
python tools/phoneuse_client.py call get_status @action.json --device-id DEVICE_ID
```

The ID above is an example. Use a new unique ID for each new input action. After a network error, query the original ID; do not automatically submit the action under another ID. Node actions require `observation_id` and `element_id` from an observation. Coordinate actions also require `observation_id`; map screenshot pixels to screen pixels using the returned scales and offsets.

The CLI omits image base64 by default, keeping image dimensions and coordinate mapping. `--screenshot-out PATH` saves the image; `--include-image-data` includes base64 explicitly. MCP returns native image content blocks. Full tree output uses `output:"legacy",compact:false`, or CLI `--full-tree`.

### Batches and optional reservations

`batch @calls.json` and MCP `phoneuse_batch` accept explicit calls, each with `device_id`, `tool`, and `arguments`. Calls execute in order on each device and independently across devices. By default, a failed or unfinished step stops subsequent calls on that device. `--summary-only` returns aggregate results. Batches do not plan tasks, fill in observation IDs, or retry actions.

For controlled tests, `acquire_device` creates a time-limited reservation. Pass its `lease_id` with actions and call `release_device` when finished. Normal actions need no reservation when the device is unreserved. Local pause and stop always remain available. See the [API reference](api.md) for execution states and retention limits.

## Service and pairing persistence

The app remembers whether the service was enabled and paused. Reopening the app restores an enabled service; stopping it explicitly prevents automatic startup on the next app open. Force-stop, reboot, and vendor battery policies may require reopening the app. Completed pairings remain, but inputs are never automatically replayed.

Deleting the client configuration, clearing app data, uninstalling the phone app, clearing browser cookies, or revoking access requires pairing again. A stable phone address simplifies browser access. MCP and Skill use the same registry by default; different `--config` paths keep their credentials separate.

## Network troubleshooting

The service uses **plain HTTP on port 8443**. It does not encrypt credentials, screenshots, or actions. There is no cloud relay. Clients need a route to the phone's IPv4 address; domain names and reverse proxies are not supported by the current Host/Origin policy.

For optional local development forwarding with ADB:

```sh
adb forward tcp:8443 tcp:8443
```

Then open `http://127.0.0.1:8443/`. On Windows PowerShell, use `curl.exe http://127.0.0.1:8443/` to avoid the `curl` alias. ADB is not required for normal network use.

When upgrading an older HTTPS configuration, change `https://` to `http://` and remove the unused `fingerprint` field. Native client credentials remain usable when identity requirements are met. Browsers treat the changed scheme as a new origin and need pairing again; uninstalling the app is unnecessary.

If input is unavailable, check accessibility, screen lock, pause state, and client authorization. If the AI host runs in the cloud and cannot reach your LAN or run a local bridge, installing the integration alone will not provide access.

## Languages and device names

App settings offer System default, 中文, and English. The selection persists on Android 11+ and integrates with Android's app-language settings on Android 13+. Notifications follow the selection. Changing language does not reset the service, pause state, or pairings. Android itself controls how the accessibility-service description is displayed in system settings.

The default device name uses the system device name where available, then brand and model; emulators use a readable fallback. Vendor APIs may return model codes rather than marketing names. A custom name takes precedence and changes neither the system device name nor installation ID.

For translations, see `app/src/main/res/values/strings.xml`, `values-zh/strings.xml`, and `res/xml/locales_config.xml`. Preserve formatting placeholders and plurals. Web strings live in `app/src/main/assets/web/i18n.js`; language changes take effect after refresh. API names, parameter keys, raw responses, and server tool descriptions keep their protocol spelling.
