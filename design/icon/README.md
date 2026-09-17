# Phone Use icon

手机轮廓代表被操作的 Android 设备，叠加的光标代表外部 Agent 的操作能力。采用深紫底色 `#211B3B`、浅紫手机轮廓和薄荷绿光标，不使用文字，以保持桌面小尺寸下的辨识度。

- `phone-use-source.png`：内置 imagegen 生成的原始透明素材，保留不改动。
- `phone-use.png`：1024 × 1024 完整方形图标，适合商店素材或后续导出。
- `phone-use-preview.png` / `phone-use-round.png`：圆角方形与圆形的桌面裁切预览。
- `prompt.txt`：生成时使用的完整提示词；使用内置 imagegen，未使用 CLI/API 回退。

Android 使用 `@mipmap/ic_phone_use`，由背景色、透明前景及独立的单色矢量图形组成。前景为 432 × 432 px 的 xxxhdpi 资源，对应 108dp 自适应图层；内容缩放并留出 12dp 边距，保护中心 66dp 安全区。系统负责圆形、圆角方形等裁切。单色版本支持系统主题图标，手机与光标之间保留间隙。

从仓库根目录运行 `powershell -ExecutionPolicy Bypass -File tools/export_icon.ps1`，可从原始素材重新导出前景和预览；无需再次生成图片。导出只涉及缩放、背景合成和平台裁切。

验证：`:app:assembleDebug` 和 `:app:lintDebug` 通过；圆形与圆角方形预览已目视检查。前景中 alpha ≥ 32 的像素距离中心最远 31.72dp，没有超出半径 33dp 的安全区。本次未安装到设备或修改应用功能。
