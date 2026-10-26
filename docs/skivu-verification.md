# Device Bridge 0.1.0 verification

Android API36 isolated emulator; real Accessibility/Messenger input, fixed host-side planning. No user account, model key or production screen data was used.

- Full independent Web-plugin SDK flow: launch notes, enter Chinese text, save, read saved text; 3 device actions and 3 fixed planning rounds.
- Foreign app launch/read denied with `APP_OUT_OF_SCOPE`.
- Wrong task capability denied with `TASK_REVOKED`.
- Password input denied with `LOCAL_INPUT_REQUIRED`; password text is excluded from exported trees.
- Sensitive test button rejected with `CONFIRM_REQUIRED` before local approval, executed once after approval, and read back as count1. This fixture button changes only a local counter.
- 72 JVM tests passed, including upstream observation/action safety and companion policy tests.
- Public APK uses a separate stable release certificate. Signature SHA-256: `4c804d97b62b8526ed21aab137edb74e624a83846f4943774ad82ca1d8040519`.

The host's compatible task SDK is developed for 0.2.42/build46. This release is a development preview, not a claim that the public host update has already activated it. Real-model task quality and physical-device/OEM behavior still need verification.

The companion has no HTTP listener. Caller identity comes from Android UID and certificate, not request fields. App scope, expiry, action budgets, fresh observation references and uncertain-result handling remain enforced. Revoking local approval invalidates active tasks and sends a best-effort revocation event to the host.

Sensitive-label detection is heuristic and does not classify every risky action. Unknown/unlabeled and coordinate taps require local confirmation. Confirmation never bypasses the upstream fresh-target validation.
