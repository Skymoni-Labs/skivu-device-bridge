# Phone Use

[English](README.md) | **简体中文**

让 AI 看见并操作 Android 手机，由你在手机上批准访问。

Phone Use 在 Android 11+ 上运行观察与控制服务，通过 JSON API、MCP 和可安装的 Agent Skill 提供界面树、截图及设备操作。手机端无需 root、ADB 或模型后端；AI 客户端独立运行，通过本地网络连接手机。

## 主要能力

- **观察屏幕**：精简界面树、JPEG 截图、按需完整树，以及等待指定应用或元素的条件观察。
- **操作应用**：点击、长按、滑动、滚动、替换或插入文字、启动应用，以及返回、主页和最近任务。
- **选择接入方式**：内置 Web 控制台、Python CLI、MCP 桥接和 `phone-use` Skill。
- **管理多台设备**：每次调用明确指定设备 ID，不同设备可独立并发。
- **保留本地控制**：在手机上批准客户端，并可随时撤销授权。

App 支持英文、简体中文和跟随系统；Web 控制台跟随浏览器语言，不支持的语言回退到英文。

## 快速开始

### 1. 构建与安装

准备 **JDK 25** 和包含 **API 37** 的 Android SDK。仓库固定使用 Gradle **9.7.1** 和 Android Gradle Plugin **9.4.0**。通过 Android Studio 或不提交到仓库的 `local.properties` 配置 SDK 路径。

构建前运行 `git submodule update --init --recursive`，获取随 APK 分发的 Skill。构建会自动将当前客户端、接口参考与 Skill 指引打包到 App。

```sh
# macOS / Linux
./gradlew :app:assembleDebug
```

```powershell
# Windows PowerShell
.\gradlew.bat :app:assembleDebug
```

将 `app/build/outputs/apk/debug/app-debug.apk` 安装到 Android 11 或更新版本的手机。构建检查和模拟器测试见[开发与验证](docs/validation.zh-CN.md)。

### 2. 启动手机服务

1. 打开 Phone Use，在 Android 设置中开启其无障碍服务。部分系统需要先为侧载应用允许“受限设置”。
2. 打开 App 后服务自动监听 8443 端口。允许通知，Android 17+ 还需按提示授予局域网权限。
3. 执行输入时保持手机亮屏、解锁，让客户端所在设备能够访问 App 显示的 IPv4 地址。
4. 小米／Redmi／POCO 用户返回 App 页面时，会尝试检测「后台弹出界面」权限。首页仅在检测到未允许或上次启动未确认时显示「后台启动应用」提示；系统不支持检测时，仅在启动未确认后提醒。「设置 → 后台启动应用」始终保留系统权限入口，可手动允许「后台弹出界面」或「后台打开新窗口」。所有厂商在启动被拒绝或未确认成功时，都会另发一条可点击打开 App 的通知；重复失败合并显示，后续启动确认成功或在 App 内关闭提醒时清除。

### 3. 配对客户端

通过 Python 客户端或 MCP 桥接连接 `https://PHONE_IPV4:8443`。逐位核对手机上的八位码，在手机批准，并在控制端确认一致。无需安装证书或复制长指纹。浏览器直连需要单独建立证书信任。

配对在客户端及 App 重启后保留，直到主动撤销或删除凭证。浏览器与 MCP 桥接默认分别配对。操作设备需要无障碍服务，配对和状态查询可在未开启无障碍时使用。

「设置 → 后台保活」默认开启，可关闭；内含电池优化、自启动和通知设置引导。建议按手机品牌允许后台运行并锁定最近任务，减少无障碍服务中断。强行停止后需重新打开应用，无障碍授权被关闭时仍需手动开启，详见[使用说明](docs/usage.zh-CN.md)。

**传输方式**：8443 端口使用 HTTPS，并绑定每台手机的公钥。首次需要双端核对八位码并确认，后续连接自动校验；旧 HTTP 凭证需重新配对。不提供互联网中继，详见[连接说明](docs/usage.zh-CN.md)。 仅通过本机 `127.0.0.1` 访问时无需开启不安全 HTTP 设置，其他 HTTP 需开启该默认关闭的设置。访问地址白名单为空时仅允许手机的连接接收端 IP；填写后只允许列表中的 IP／域名，本机控制台也受限制，仍需配对和鉴权。

## 接入 AI

推荐在 App 的 **连接 AI** 卡片中选择手机地址，点击提示词右侧的复制按钮，将整段提示词发给 AI。AI 会读取链接指引，从手机下载完整 Skill，校验、安装后发起配对。用户核对八位码并在手机批准，再明确告诉 AI 核对一致即可继续。宿主需要 **Python 3.10+**；Skill 随 App 更新。

AI 宿主必须能执行程序并访问手机地址；普通云端聊天无法直接访问手机局域网。原有 GitHub 安装仍可使用，需要 **Node.js 22.20+**、**Git** 和 **Python 3.10+**：

### Agent Skill

```sh
npx skills add nidhoggfgg/phone-use-skills --skill phone-use
```

按提示选择 AI 客户端和安装范围，然后告诉 AI：

> 使用 $phone-use 连接 https://192.168.1.20:8443，显示核对码，等我在手机批准后查看当前界面。

### MCP

让 AI 客户端通过以下命令启动 stdio 服务：

```sh
npx -y --package=github:nidhoggfgg/PhoneUse phone-use
```

在相同 `npx` 前缀后使用 `phone-use print-config` 或 `phone-use print-config --host codex` 可生成配置片段。桥接管理配对和凭证，不向模型暴露 token。

[AI 接入指南](docs/integrations.zh-CN.md)包含完整命令、Windows 配置、首次连接、更新和离线包说明。GitHub 安装要求仓库对使用者可访问；包内名称不代表已发布到 npm registry。

## Python CLI

CLI 仅依赖 Python 3.10+ 标准库。在仓库根目录执行：

```sh
python tools/phoneuse_client.py pair --url https://192.168.1.20:8443 --name laptop
python tools/phoneuse_client.py list-devices
python tools/phoneuse_client.py call get_capabilities --device-id DEVICE_ID
python tools/phoneuse_client.py call observe --device-id DEVICE_ID --screenshot-out screenshot.jpg
```

将 `DEVICE_ID` 替换为配对返回的 ID。凭证由客户端保存在 `~/.phoneuse.json`，不要提交或分发该文件。动作调用、JSON 参数、重连和批量操作见[使用指南](docs/usage.zh-CN.md)。

## 执行方式与限制

动作只执行一次。`launch_app` 最多等待五秒确认目标应用进入前台；未确认时返回结果未知及排查提示。其他等待、观察与业务结果验证由客户端明确安排。每个输入动作必须有唯一的 `request_id`；网络异常后查询原请求，不要更换 ID 重放动作。

`executed` 只表示 Android 接受操作或完成手势，不代表应用内任务成功。树与截图分别采集，受保护窗口、自定义控件、键盘和厂商后台策略可能影响可用性。实体设备覆盖尚不完整，详见[验证指南](docs/validation.zh-CN.md)。

## 文档

| 指南 | 内容 |
| --- | --- |
| [使用与连接](docs/usage.zh-CN.md) | Web 控制台、CLI、配对、语言设置、连接排查 |
| [AI 接入](docs/integrations.zh-CN.md) | Skill 与 MCP 安装、配置、更新、离线分发 |
| [API 参考](docs/api.zh-CN.md) | 工具、参数、观察语义、错误及请求保留规则 |
| [开发与验证](docs/validation.zh-CN.md) | 构建检查、模拟器测试页、实机验收 |
| [贡献指南](CONTRIBUTING.zh-CN.md) | 提交修改、报告问题、文档约定 |
| [安全策略](SECURITY.md) | 支持版本及私密漏洞报告方式 |

上述指南均提供英文和简体中文内容。

## 许可证

本项目采用 [MIT 许可证](LICENSE)。
