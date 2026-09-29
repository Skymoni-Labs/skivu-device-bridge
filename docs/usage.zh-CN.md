# 使用与连接

[English](usage.md) | **简体中文**

## Web 控制台

App 的“打开 Web 控制台”始终打开 `http://127.0.0.1:8443/`，本机回环 HTTP 始终可用，仍需正常配对。远程浏览器默认使用 `https://192.168.1.20:8443/`，需要独立建立浏览器证书信任；浏览器六位授权码不能替代 TLS 身份验证。控制台支持 API 搜索、参数表单、JSON 编辑及显式截图。

设置中的“允许不安全的 HTTP 连接”可开启同端口的远程 HTTP，默认关闭，重启后保留，切换立即生效。关闭时已有的非本机 HTTP 请求也会断开。HTTP 不加密屏幕内容、操作和凭据，仍需配对和鉴权。HTTP 和 HTTPS 使用独立浏览器 Cookie，切换协议可能需要重新配对。

App 展示非回环网络地址及设置中配置的公网 IP／域名，默认仅展示 HTTPS，开启开关后额外展示 HTTP。公网设置仅填写地址，不含协议、端口和路径，清空可移除。展示地址不代表公网可达，路由和 8443 端口映射仍由部署环境负责。

浏览器用 HttpOnly Cookie 保存授权。关闭标签页、重启浏览器或 App 通常无需重新配对。可在手机撤销客户端，或在网页解除当前浏览器配对。清除 Cookie 或更换浏览器访问地址后需重新配对。网页不展示或导出长期 token。

查询或重试同一动作时保留原 `request_id`；仅为新动作生成新 ID。连接恢复不重放输入。

## Python 客户端

需要 Python 3.10+，无第三方依赖。在仓库根目录运行：

```sh
python tools/phoneuse_client.py pair --url https://192.168.1.20:8443 --name laptop
python tools/phoneuse_client.py list-devices
python tools/phoneuse_client.py call observe --device-id DEVICE_ID --screenshot-out screenshot.jpg
python tools/phoneuse_client.py call observe --device-id DEVICE_ID --full-tree
```

`pair` 显示本地计算的八位码。逐位核对手机上的码，在手机批准，然后在 CLI 输入 `yes`。使用 MCP 时，告诉 AI 已核对一致，AI 才能携带 `confirm_pairing:true` 继续调用 `phoneuse_connect`。已完成配对自动复用；未完成请求两分钟过期，手机服务重启后清除。

默认配置为 `~/.phoneuse.json`。需要自定义位置时，在**子命令之前**添加 `--config /absolute/path/registry.json`。即使只有一台设备，也要传入返回的 `DEVICE_ID`。地址变化时用 `pair --url NEW_URL --device-id DEVICE_ID` 更新。桥接发送凭证前核验安装身份；旧配置没有可信设备 ID 时需要重新批准。

### JSON 参数与动作

为避免不同终端的引号规则影响参数，将 JSON 保存为 UTF-8 文件 `action.json`：

```json
{"request_id":"home-001"}
```

```sh
python tools/phoneuse_client.py call home @action.json --device-id DEVICE_ID
python tools/phoneuse_client.py call get_status @action.json --device-id DEVICE_ID
```

此 ID 仅为示例，每个新输入动作应使用新的唯一 ID。网络错误后查询原 ID，不要自动更换 ID 重放。节点操作需要观察返回的 `observation_id` 和 `element_id`；坐标动作也需要 `observation_id`，并依据截图的缩放与偏移换算屏幕坐标。

CLI 默认省略图片 base64，保留尺寸及坐标映射。`--screenshot-out PATH` 保存图片，`--include-image-data` 显式返回 base64。MCP 返回原生图片内容块。完整树使用 `output:"legacy",compact:false` 或 CLI `--full-tree`。

### 批量调用与可选占用

`batch @calls.json` 和 MCP `phoneuse_batch` 接受显式调用，每项提供 `device_id`、`tool` 和 `arguments`。同设备按顺序执行，不同设备独立并发。默认在某设备出现失败或未完成步骤后停止其后续调用。`--summary-only` 只返回汇总。批量调用不规划任务、不自动填入观察 ID，也不重试动作。

连续测试可通过 `acquire_device` 取得限时占用，在动作中携带 `lease_id`，结束时调用 `release_device`。设备未被占用时可直接操作。手机本地暂停和停止始终有效。执行状态和保留上限见 [API 参考](api.zh-CN.md)。

## 服务与配对恢复

App 记住服务启用及暂停状态。重开 App 会恢复此前启用的服务；主动停止后不会因重开而自动启动。系统强行停止、手机重启和厂商省电限制可能需要重新打开 App。已完成配对保留，但不会自动重放输入。

删除客户端配置、清除 App 数据、卸载手机 App、清除浏览器 Cookie 或撤销授权后需重新配对。固定手机地址有助于稳定浏览器访问。MCP 与 Skill 默认共用配置，通过不同的 `--config` 路径可分别保存凭证。

## 连接排查

服务在 **8443 同端口接收 HTTPS 和 HTTP**。MCP／Python 客户端仍要求公钥绑定的 HTTPS，不自动降级。远程 HTTP 需要显式开启上述设置。Host 必须匹配连接接收端 IP 或已配置的公网地址，Origin 必须与请求协议及地址完全同源；转发头不改变连接来源是否为回环的判定。不提供云端中继或自动端口映射。详见[传输安全](transport-security.zh-CN.md)。

开发时可选用 ADB 本机转发：

```sh
adb forward tcp:8443 tcp:8443
```

随后使用 `python tools/phoneuse_client.py pair --url https://127.0.0.1:8443` 连接。正常网络连接不需要 ADB。

同时更新手机和桥接。已有 HTTP 地址会在本地迁移为 HTTPS，但旧凭证会丢弃，必须重新核对配对。TLS 密钥变化不会自动接受；若独立核实确实进行了重装／重置，先在手机撤销旧客户端，再执行 `python tools/phoneuse_client.py forget-device --device-id DEVICE_ID`，然后重新配对。

无法输入时检查无障碍、锁屏、暂停和客户端授权。云端 AI 如果不能访问局域网或运行本地桥接，仅安装接入文件无法连接手机。

## 语言与设备名称

App 设置支持跟随系统、中文和 English。Android 11+ 会保存选择，Android 13+ 与系统应用语言同步；通知跟随选择。切换语言不重置服务、暂停状态或配对。系统设置中的无障碍服务说明由 Android 决定显示方式。

默认设备名优先使用系统名称，否则回退到品牌和型号；模拟器使用易读名称。厂商接口可能只返回型号编码。手动名称优先，不修改系统设备名或安装 ID。

Android 翻译位于 `app/src/main/res/values/strings.xml`、`values-zh/strings.xml` 和 `res/xml/locales_config.xml`，需保留格式化占位符和复数规则。Web 文案位于 `app/src/main/assets/web/i18n.js`，刷新后应用语言变化。API 名称、参数键、原始响应和服务端工具说明保持协议原样。
