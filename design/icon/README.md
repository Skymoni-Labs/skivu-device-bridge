# Phone Use icon

**English** | [简体中文](README.zh-CN.md)

The phone outline represents the Android device; the cursor represents an external agent's actions. A dark purple background (`#211B3B`), light purple phone, and mint cursor keep the icon recognizable at small sizes without text.

- `phone-use-source.png`: original transparent imagegen asset, preserved unchanged.
- `phone-use.png`: 1024 × 1024 square icon for store artwork or further exports.
- `phone-use-preview.png` / `phone-use-round.png`: rounded-square and circular launcher previews.
- `prompt.txt`: original generation prompt; generated with built-in imagegen, without CLI/API fallback.

Android uses `@mipmap/ic_phone_use`, composed of a background, transparent foreground, and separate monochrome vector. The foreground is 432 × 432 px at xxxhdpi, corresponding to a 108dp adaptive layer. Content includes 12dp padding to protect the central 66dp safe area. Android applies circle/rounded-square masks. The monochrome version supports themed icons, retaining a gap between the phone and cursor.

From the repository root, run `powershell -ExecutionPolicy Bypass -File tools/export_icon.ps1` to re-export the foreground and previews from the source. This only resizes, composites, and masks existing artwork; it does not regenerate images.

The original validation recorded successful `:app:assembleDebug` and `:app:lintDebug`, plus visual review of circular and rounded-square previews. Foreground pixels with alpha ≥ 32 were at most 31.72dp from center, within the 33dp safe radius. That work did not install to a device or change app behavior. These are historical results, not checks rerun for this document.
