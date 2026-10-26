# Skivu Device Bridge

Standalone Android 11+ accessibility companion for user-authorized Skivu device tasks. The Skivu build uses a versioned local Android Messenger service, with real caller-UID/signature approval; it opens **no HTTP listener** and needs no root or ADB in daily use.

## Provenance

Derived from [PhoneUse v1.3.0](https://github.com/nidhoggfgg/PhoneUse/tree/v1.3.0), commit `4eb7cddf63bc53b5be795b5b17fa536994cc5897`, under MIT. Original history, source, license and documentation are retained. The original README is available in [docs/upstream-README.md](docs/upstream-README.md).

The `skivu/` build reuses upstream `core` and `device` source files through a deterministic Gradle Sync task. Upstream web servers, skill distribution and Compose UI are not compiled into this companion. The original root build remains available for upstream reference; its workflows are manual or restricted to upstream-prefixed tags to avoid confusing the two distinct products.

## Build and test

Use JDK17, Android SDK36 and Gradle8.14:

```sh
gradle -p skivu :app:assembleDebug :fixture:assembleDebug :app:testDebugUnitTest
# Alternatively cd skivu and use its checksum-pinned Gradle wrapper.
```

The application ID is `org.skivu.devicebridge`. `org.skivu.devicefixture` is a separate **test-only** notes app. Debug APKs are for verification; public installable artifacts must be signed with the separately retained release key. No release signing material belongs in this repository.

## Local protocol

Discover services with action `org.skivu.devicebridge.V1`; bind explicitly to a user-selected component. Send a Messenger message with a JSON string under Bundle key `json`, an integer correlation ID in `arg1`, and a `replyTo` Messenger. The service obtains `sendingUid` from Android and never accepts a self-reported caller identity.

Methods: `hello`, `pair`, `capabilities`, `list_apps`, `begin`, `call`, `end`. All operations except `hello/pair` require manual client approval. `begin` accepts exact `allowedPackages`, a maximum action count and expiry; it returns a process-scoped task capability. `call` requires that capability and preserves upstream request IDs and observation references. Revocation invalidates active tasks. One task owns device input at a time.

Sensitive-label, coordinate and unlabeled tap requests require local one-shot approval. Labels cannot reliably identify every dangerous action; only bounded, explicitly approved tasks should run. Authorization UI and the controller app cannot be task targets. Unknown receipts require human reconciliation, never a replacement request that silently replays an operation.

Default screenshots/trees and action arguments remain in process memory. No model key or Skymoni credential is accepted by the companion. Supported-model inference belongs to the host's existing guarded path.

## Repositories

- Personal primary: https://github.com/skyjjgw/skivu-device-bridge
- Organization copy: https://github.com/Skymoni-Labs/skivu-device-bridge
- Independent UI plugin: https://github.com/skyjjgw/skivu-device-control

MIT, including upstream copyright and permission notices. This is an early integration; device execution tests and real-model tests must be reported separately.
