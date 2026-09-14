# 失控桥（cachito-shikong4-bridge）

用一部闲置安卓手机当蓝牙发射器，把「失控 4.0」接到你自己的服务器上，让 AI 通过 MCP 直接控制它 —— 也可以只用手机浏览器打开一个网页手动控制。全部自建，数据不出自己的机器。

- 复刻了官方 App 的 **19 个花样模式**（逐帧抓包还原，不是自己编的波形）
- 吮吸端 / 入体端**两路完全独立**，可以各跑各的模式
- 服务端 Python，手机端 Kotlin + Compose，全部开源，APK 在 [Releases](../../releases) 直接下

---

## 原理与架构

失控 4.0 不走「蓝牙连接」，它靠 **BLE 广播**控制：手机把一段 16 字节的命令塞进广播包里不停地播，玩具听见就照做。这带来两个后果：

1. **手机浏览器发不了 BLE 广播**（Web Bluetooth 只能连接，不能广播），所以纯网页方案做不了 —— 必须有一个原生 App。
2. 广播是**单向、无连接**的，玩具是「记忆命令型」：收到一次就一直执行下去，不主动发关命令它不会停。

所以这套东西拆成两半：一个安卓 App 常驻后台负责发广播，一台服务器负责存状态、跑花样、接 AI。两边用 WebSocket 连着。

```
AI（MCP 客户端）→ MCP 服务 :8896 → 中转服务 relay :8895 ⇄ WebSocket ⇄ 安卓 App → BLE 广播 → 失控 4.0
                                        ↑
                                   手机浏览器（手动控制页）
```

`relay` 是唯一的状态真身：档位、正在跑的模式都存在它那儿。App 只是个「听指挥的发射器」，AI 和手动网页改的都是 relay 的状态，relay 再推给 App。

## 特点

- **只靠手机就能连**，发射端是手机，不用把电脑搬到床边，也不用买蓝牙 dongle。想用 Windows 电脑做发射端的，请看 [Esther7314/cachito-ble-control](https://github.com/Esther7314/cachito-ble-control)，本项目的协议就来自那里；两条路互不冲突，这里只做手机这条。
- **放后台就行**，不需要悬浮窗、不需要无障碍权限，锁屏也在跑。
- **两端各管各的**：吮吸端和入体端可以同时跑两个不同的模式，互不干扰。
- **19 个官方模式逐步复刻**：从官方 App 的蓝牙日志里把每一步「几档、播几秒」原样扒出来，不是近似。
- **四盏灯看断在哪**：口令 / 后台服务 / 服务器连接 / 蓝牙广播，哪一环红了一眼就知道。
- **自己编模式**：模式表是一个 JSON，一条就是"吮吸端一串（档位, 秒）+ 入体端一串"，加一条重启即生效，App 和 AI 那边自动多出来。
- **开箱即用**：Release 下 APK 装上、填服务器地址和口令、点启动，就能用；不想自己编译就不用装 Android Studio。
- **安卓专用**：发射端只做了安卓；iPhone 未开发（iOS 能不能发这种广播没验证）。

## 已验证环境

| | |
|---|---|
| 玩具 | 失控 4.0（BLE 广播协议） |
| 手机 | iQOO Z8 / Android 14（vivo OriginOS） |
| 服务器 | 任意能跑 Python 3.10+ 的 Linux VPS，1 核 1G 足够 |

别的安卓手机只要支持 BLE 广播（`BLUETOOTH_ADVERTISE`，基本上 Android 8 以后都行）理论上都可以，只是后台保活的设置项各家不一样。

---

## 安装

### 一、服务端

```bash
# 1) 放代码
sudo mkdir -p /opt/shikong-bridge
sudo cp server/*.py server/patterns.json server/relay.html /opt/shikong-bridge/

# 2) 装依赖
sudo pip3 install -r server/requirements.txt      # aiohttp + mcp

# 3) 生成口令（relay 和 MCP 服务都读这个文件）
openssl rand -hex 8 | sudo tee /opt/shikong-bridge/token
sudo chmod 600 /opt/shikong-bridge/token

# 4) 起服务
sudo cp server/systemd/*.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now shikong-relay shikong-mcp
```

两个服务：`shikong-relay` 听 8895（状态机 + 花样引擎 + WebSocket + 手动网页），`shikong-mcp` 听 8896（给 AI 用的 MCP，内部通过 127.0.0.1 调 relay）。

**口令是整套系统唯一的门禁**，换口令要改 `/opt/shikong-bridge/token` 然后把两个服务都 restart（它们只在启动时读一次）。

### 二、公网入口（二选一）

手机要能从外网连到 8895。两种做法：

**A. 直连端口**（简单，但是明文 ws://）

```bash
sudo ufw allow 8895/tcp
```

App 里填 `ws://YOUR_VPS_IP:8895/phone/ws`。

**B. Cloudflare Tunnel**（推荐，有 TLS，不用开端口）

在 tunnel 配置里加一条 path 规则指到 `http://localhost:8895`，**要放在更宽的通配规则前面**：

```yaml
ingress:
  - hostname: YOUR_DOMAIN
    path: ^/shikong(/.*)?$
    service: http://localhost:8895
  - hostname: YOUR_DOMAIN
    service: http://localhost:80
  - service: http_status:404
```

App 里填 `wss://YOUR_DOMAIN/shikong/phone/ws`。relay 会自动剥掉 `/shikong` 前缀，两种路径都认。

### 三、手机端

1. 从 [Releases](../../releases) 下载 `shikong-bridge-x.y.z.apk` 装上（是 debug 签名，安装时系统会提示「来自未知来源」，正常）。
2. 打开「失控桥」→ 第 ① 行红灯「口令没填」→ 点一下填入服务器上那串口令 → 保存。
3. 设置页填服务器地址（上一步的 `ws://` 或 `wss://` 地址）。
4. 回到首页点「启动后台服务」（**它不会自己启动，必须手动点一次**）。
5. 四盏灯全绿 = 通了。

**vivo / iQOO 必做的两个后台开关**（不做的话锁屏几分钟就断）：

- **允许后台高耗电**：设置 → 应用与权限 → 应用管理 → 失控桥 → 耗电管理 → 打开「允许后台高耗电」。
- **自启动**：i 管家 → 应用管理 → 权限管理 → 自启动管理 → 打开「失控桥」。

App 设置页里有按钮直接跳到这两个页面，还有一个「申请忽略电池优化」也顺手点一下。小米 / 华为 / OPPO 的对应开关名字不同，找「自启动」和「省电策略：无限制」即可。

不想装 App 也可以用手机浏览器打开 `https://YOUR_DOMAIN/shikong/?token=你的口令`（或 `http://YOUR_VPS_IP:8895/?token=...`）手动控制 —— 但**广播还是得靠 App 发**，网页只是遥控器。

### 四、把 MCP 挂给 AI

MCP 服务是 streamable-http，地址 `http://YOUR_VPS_IP:8896/mcp`。

**Claude Code：**

```bash
claude mcp add --transport http shikong http://YOUR_VPS_IP:8896/mcp
```

**Claude Desktop**（`claude_desktop_config.json`）：

```json
{
  "mcpServers": {
    "shikong": {
      "type": "http",
      "url": "http://YOUR_VPS_IP:8896/mcp"
    }
  }
}
```

> ⚠️ **8896 自己没有鉴权**（它只是个本机代理，口令用在它和 relay 之间）。别把 8896 直接暴露到公网 —— 放在隧道后面、绑到 `127.0.0.1`、或者用防火墙只放行你自己的 IP。想让 MCP 服务只听本机，把 `mcp_server.py` 里 `host="0.0.0.0"` 改成 `127.0.0.1`。

---

## MCP 工具

| 工具 | 参数 | 作用 |
|---|---|---|
| `shikong_suck(level)` | `level` 0–100 | 吮吸端（体外那头）手动设档，0 = 关。会清掉该端正在跑的模式 |
| `shikong_invib(level)` | `level` 0–100 | 入体端（震动那头）手动设档，0 = 关。两端完全独立 |
| `shikong_pattern(name, part)` | `name` 模式名；`part` = `suck` / `invib` / `both`（默认 both） | 跑一个花样模式，档位按序列自动起伏并一直循环 |
| `shikong_pattern_list()` | | 列出所有模式：名字、说明、两端各自「几档播几秒」的完整一圈 |
| `shikong_stop()` | | 全停：两端归零、模式清空 |
| `shikong_status()` | | 当前档位、在跑什么模式、**手机 App 在不在线** |

`level` 就是官方 App 上滑块的刻度 0–100。手机不在线时命令只会存在 relay 里，等手机上线后才真正播出去。

## 内置的 19 个模式

全部从官方 App 的蓝牙日志里逐帧还原。两端的循环长度常常不一样（比如「暗涌」吮吸 5.5 秒一圈、入体 8 秒一圈），所以跑起来会一直错开，这是官方的设计。

| # | 名字 | 说明 |
|---:|---|---|
| 1 | 玄妙 | 两端同步，轻重每 2 秒交替，规律好跟 |
| 2 | 暗涌 | 吮吸 5.5 秒一轮、入体 8 秒一轮，错开涨落，猜不到下一下 |
| 3 | 旧梦 | 两端各自阶梯式爬升再落回，1.5 秒一步 |
| 4 | 狂澜 | 吮吸 8.5 秒一圈、入体 8.5 秒一圈，最高到 100 档 |
| 5 | 觊觎 | 吮吸 6 秒一圈、入体 8.5 秒一圈，最高到 84 档 |
| 6 | 逡巡 | 吮吸 6 秒一圈、入体 6 秒一圈，最高到 92 档 |
| 7 | 谵妄 | 吮吸 6 秒一圈、入体 7 秒一圈，最高到 100 档 |
| 8 | 噬嗑 | 吮吸 6.5 秒一圈、入体 6 秒一圈，最高到 90 档 |
| 9 | 大热 | 吮吸 7 秒一圈、入体 6 秒一圈，最高到 80 档 |
| 10 | 渊薮 | 吮吸 7 秒一圈、入体 7.5 秒一圈，最高到 88 档 |
| 11 | 纵念 | 吮吸 8 秒一圈、入体 7.5 秒一圈，最高到 88 档 |
| 12 | 欲溺 | 吮吸 13 秒一圈、入体 7.5 秒一圈，最高到 90 档 |
| 13 | 十一点 | 吮吸 7 秒一圈、入体 7.5 秒一圈，最高到 62 档 |
| 14 | 探春 | 吮吸 7.5 秒一圈、入体 7.5 秒一圈，最高到 70 档 |
| 15 | 翡冷翠 | 吮吸 7.5 秒一圈、入体 7.5 秒一圈，最高到 76 档 |
| 16 | 云不归 | 吮吸 7.5 秒一圈、入体 8.5 秒一圈，最高到 90 档 |
| 17 | 凝液 | 吮吸 6 秒一圈、入体 8.5 秒一圈，最高到 28 档 |
| 18 | 失心疯 | 吮吸 5.5 秒一圈、入体 5.5 秒一圈，最高到 75 档 |
| 19 | 引诱 | 吮吸 5.5 秒一圈、入体 5.5 秒一圈，最高到 81 档 |

## 自己加模式

`patterns.json` 是纯数据，**加模式不用改代码**。格式（v2）：

```json
{
  "name": "慢潮",
  "order": 20,
  "desc": "自制。两端错开的慢涨落",
  "official": false,
  "suck":  [[20, 3], [45, 2.5], [70, 2], [30, 4]],
  "invib": [[15, 4], [50, 3], [80, 2.5]]
}
```

- `suck` / `invib` 各是一串 `[档位, 秒数]`：这一档保持几秒，走完自动从头再来。档位 0–100，秒数支持小数。
- 两端可以只写一个（另一端留空数组就不参与）；**两端长度取互质的秒数**，跑起来错位感更强。
- 加完一条，重启 `shikong-relay` 即生效，MCP 那边 `shikong_pattern_list()` 立刻能看到。

想复刻官方以外的模式，见 [`docs/录官方模式.md`](docs/录官方模式.md)：打开手机的蓝牙 HCI 日志 → 官方 App 每个模式跑 60 秒 → 导出 bugreport → `python3 tools/extract_patterns.py 日志 模式名1 模式名2 ...` 自动生成上面这个格式。

## 安全与停机

**请务必读完这一节。**

- **玩具是记忆命令型（latch）**：收到一条命令就一直执行下去，广播停了它也不停。**结束时必须显式发关命令** —— `shikong_stop()`、手动页的「全停」、或通知栏的「全停」按钮。掐 App、关手机、断网都**不会**让它停。
- App 的「停止后台服务」会先播一遍全关再退出，这是正常退出方式。但如果手机突然没电 / 被系统杀掉，玩具会保持最后一条命令，这时候只能用玩具自己的实体按键关。
- **口令绝不内置进 APK**。本项目 0.1.2 之前犯过这个错：把安装包链接经微信/QQ 传给自己，**腾讯的安全扫描把 APK 丢进云端模拟器实际跑了一遍**，用内置口令真的连上了 relay，把本人顶下线（日志里那台设备来自腾讯云）。结论：APK 会被第三方跑起来，内置密钥等于公开。传安装包走别的渠道，口令在手机上手输一次。
- 同一时刻只允许一台手机连着，新连接会把旧的顶下线，防止两台同时发冲突的广播。
- 8895 的所有接口都要口令（WebSocket 用 `?token=`，HTTP 用 `Authorization: Bearer`）。唯一免口令的是 APK 下载路径，它带一段随机后缀。
- 别把 8896（MCP）裸奔到公网，它没有自己的鉴权。

## 文档

- [`docs/接口约定.md`](docs/接口约定.md) —— 服务端 ⇄ App 的完整协议：端口、口令、状态模型、WebSocket 消息、HTTP 接口、**BLE 命令的字节格式和档位公式**。
- [`docs/录官方模式.md`](docs/录官方模式.md) —— 怎么抓蓝牙日志、怎么用 `tools/extract_patterns.py` 提取。
- [`docs/协议与调研.md`](docs/协议与调研.md) —— 这套协议是怎么被逆向出来的，三个上游开源项目各自的贡献和短板。

## 仓库结构

```
android/   安卓 App 源码（Kotlin + Compose），CI 会自动编译出 debug APK
server/    relay + MCP + 编解码 + 模式表 + 手动控制网页 + systemd 单元
tools/     从蓝牙 HCI 日志里提取模式的脚本
docs/      接口约定、录模式步骤、协议调研
```

自己编译：`cd android && ./gradlew assembleDebug`，产物在 `android/app/build/outputs/apk/debug/`。每次 push 到 main，GitHub Actions 会跑单元测试并把 APK 作为 artifact 传上去。

> 仓库里带了一把固定的调试 keystore（`android/app/keystore/`，口令就是惯例的 `android`），这样不同机器、不同 CI 跑出来的包签名一致，可以直接覆盖安装。它**不是**发布签名。
> Release 里的 v0.1.5 安装包是更换这把 keystore 之前签的，所以从源码编译的包和它签名不同 —— 两者互换时手机上要先卸载再装（存的口令和地址会丢，重填一次即可）。

## 致谢

- [Esther7314/cachito-ble-control](https://github.com/Esther7314/cachito-ble-control) —— 目前对这一代玩具广播协议记录得最完整的项目：帧结构、校验和、命令表、档位公式、latch 行为、真包样本。本项目的编解码就是照着它实现的。
- [yoruuuchan/cachito-ble-mcp-relay](https://github.com/yoruuuchan/cachito-ble-mcp-relay)（MIT）—— 「MCP → 服务器 → WebSocket → 安卓 App → 广播」这套架构的原型，`android/` 的 Gradle 骨架和 BLE 广播调用写法来自它。
- [Moonriseandset/cachito-shikong-mcp](https://github.com/Moonriseandset/cachito-shikong-mcp) —— 在 macOS 上独立验证了同一套协议，并指出官方花样模式本质是「强度按节奏起伏」而不是专用指令。

这三个项目各自解决了一块，本项目把它们拼起来，补上了手机端发射和官方模式的完整复刻。

## 免责声明

- 本项目基于**社区逆向**得到的广播协议实现，**不是厂商的官方接口**，随时可能因固件更新而失效。与设备厂商无任何关联，也未获其认可或支持。
- 仅供在**你自己拥有的设备**上使用。
- 涉及他人时，**必须双方事先知情并同意**，并约定好随时可以叫停的方式。把控制权交给 AI 或远程的人之前，先确认你随手就能停下来（实体按键、`shikong_stop()`、手动页的全停按钮）。
- 成年人专用。请阅读上面「安全与停机」一节，了解 latch 行为再使用。
- 按 MIT 协议原样提供，不承担任何后果。

## 许可

MIT，见 [LICENSE](LICENSE)。
