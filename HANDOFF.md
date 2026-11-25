# TapeTape v2 交接

## 一、整体情况

| 目录 | 内容 |
|---|---|
| `core/` | Rust 核心 `tapetape-core`（账号、消息、群、钱包、红包、小店、备份、代理），通过 UniFFI 给两端用；`taperelay/` 是协议库 |
| `ios/` | SwiftUI app，iOS 16+，bundle id `org.tapetape.v2`（开发用，待定） |
| `android/` | Compose app，minSdk 26，applicationId `org.tapetape.v2`（开发用，待定） |
| `spec/` | 规格：`ARCHITECTURE.md`、`PROTOCOL.md`、`CORE-API.md` |

- **消息**：走 TapeRelay 官方节点，免费，不需要电路。**可用。**
- **大附件**（超过 170 KB 的图片、视频、文件）：**已开通**。签名后台 `files-backend/` 部署在 tapetape.org（电路 105.2.0.tape，节点 relay3、relay5），两端构建配置已填 `https://tapetape.org`，单个文件最大 100 MB。
- **RPC 中转**：两端读链、发交易先走 `https://tapetape.org/rpc/<链号>`（同一个后台），自有节点地址只在服务器上，失败再用公共节点。tapekit.org 网关也把它当 X Layer 的第三家节点。
- **钱包**：X Layer（OKB、BEM）+ BSC（BNB、BEM、USDT）。**主网模拟通过，未实测。**
- **红包 / 转账**：X Layer 和 BSC 上的合约（同一份代码），真正上链，7 天后可退回。**主网模拟通过，未实测。**
- **链上小店**：卖家自己的钱包在 X Layer 上一键开店。**主网模拟通过，未实测。**
- **广场**：App 里直接打开 OKX 的 X Layer 黑客松投票页（`https://web3.okx.com/zh-hans/xlayer/hackathon-voting`），不再使用原来的匿名留言板。
- **节点名单**：注册时直接读 X Layer 上的 TapeRelay 目录合约和质押合约，再逐台核对节点自报的运营方编号，不经过 www.taperelay.com。
- **和旧版不互通**：v2 的消息格式是新的，旧版 TapeTape / TapeTape Relay 用户收不到 v2 的消息，反过来也一样。聊天记录不迁移。
- **群最多 500 人（含自己）**：群没有服务器，发一句话手机要给每个成员各发一份，500 人的群每句话要发 500 次。上千人、上万人的群要改架构：成员名单改为增量同步，消息改用群共享密钥只加密一次，由 TapeRelay 节点代为分发（节点要新增这项能力）。
- **一个身份只能在一台设备上收消息**：同一助记词不要同时登录旧版和 v2，也不要同时登录两台手机。

## 二、待办

| # | 事项 | 谁 |
|---|---|---|
| 1 | 定包名（沿用 `org.tapetape.relay` 还是新的） | 项目负责人 |
| 2 | 主网小额实测钱包、红包、转账、小店 | 测试 |
| 3 | 开通电路、部署后台，把后台地址写进两端构建配置 | 电路持有人、后台运维、开发 |
| 4 | iOS 通用链接（AASA） | 网站运维 |
| 5 | 真机测试 | 测试 |
| 6 | 证书、签名、上架 | 发布负责人 |
| 7 | 部署新红包合约，把地址发给开发（见第 5 节） | 合约主人 |

### 1. 主网实测（测试）

准备两个钱包，分别导入 iOS 和 Android：

| 钱包 | 资金 | 测什么 |
|---|---|---|
| A（发送方） | X Layer 约 0.1 OKB；BSC 约 0.005 BNB + 1 USDT | 开店、发布商品、发红包、转账、提现 |
| B（接收方） | X Layer 约 0.005 OKB；BSC 约 0.002 BNB | 领红包、收转账 |

每一步核对链上交易哈希。重点看：
- 提现的“实际到账”和链上到账一致，手续费另外从原生币扣。
- 红包领完、过期后不能再领；发送方 7 天后能退回剩余。
- 小店开好后，用浏览器打开分享链接能看到商品，点“联系卖家”能加好友。
- 导入旧版助记词后，钱包地址和旧版一致。

### 2. 大附件签名后台与 RPC 中转（已部署）

- 代码：`files-backend/`（Node 22.13+，`npm test`），说明见 `files-backend/README.md`。
- 服务器：tapetape.org 那台，服务 `tapetape-files`（只听本机 3010），nginx 把 `/api/relay/sign` 和 `/rpc/` 转给它。
- 更新程序：`bash files-backend/deploy/install-tapetape-org.sh`（不动密钥和节点配置）。
- 换电路密钥：控制台下载 .env 后 `scp <文件> ubuntu@tapetape.org:/tmp/k.env && ssh -t ubuntu@tapetape.org sudo /opt/tapetape-files/set-key /tmp/k.env`。
- 换自有节点：`ssh -t ubuntu@tapetape.org sudo /opt/tapetape-files/set-rpc 196`（BSC 用 56）。
- 安卓下载固定短链接 https://tapetape.org/apk：跳到 TapeRelay 分享链接（安装包加密存电路 105.2.0.tape 的 relay3/relay5）。发新版：apk 传到服务器 /tmp 后运行 `sudo /opt/tapetape-files/share-file --latest /tmp/<文件>.apk TapeTape-<版本>.apk`，传完自动切换；手动改指向用 `set-apk '<分享链接>'`（指向写在 /etc/nginx/tapetape-apk.conf，上一版留 .prev）。
- 中转只放行 App 和网关用到的方法，每个 IP 每分钟 600 次、总量每分钟 6000 次；`eth_getLogs` 跨度不超过 1 万块。

### 3. iOS 通用链接（网站运维）

`https://i.tapetape.org/.well-known/apple-app-site-association` 的 `applinks.details` 里加 `"<TEAMID>.org.tapetape.v2"`（包名定了以后换成正式的）。邀请落地页本身已部署在 i.tapetape.org（腾讯云 43.165.171.171，和官网同一台）。不加的话，iOS 上点邀请链接会打开 Safari。

### 4. 证书与上架（发布负责人）

**iOS**：详见 `ios/PLATFORM.md`「Release and signing」。
- App `org.tapetape.v2`，分享扩展 `org.tapetape.v2.share`，App Group `group.org.tapetape.v2`（两个都要开）。
- 能力：App Groups、Associated Domains。**不需要推送**。
- 设置 `DEVELOPMENT_TEAM`，然后按文档里的 `xcodebuild archive` 命令打包。

**Android**：详见 `android/BUILD.md`。
- 复制 `android/keystore.properties.example` 为 `android/keystore.properties`，填正式签名。这个文件不进 git。没有它也能打包，只是没签名。
- 每次发版改 `app/build.gradle.kts` 里的 `versionCode`、`versionName`（现在是 2 / 2.0.0）。
- 上架用 AAB（约 26 MB，手机实际下载约 9 MB）。

**改包名**：iOS 改 `ios/project.yml` 里的 bundle id、App Group 和 `ios/PLATFORM.md` 里列出的各处；Android 改 `applicationId`。改 App Group 后，已装开发版的数据不会自动带过去。

### 5. 红包合约

旧合约 `MailChatRedPacket`（BSC `0xa71f…A7F3`、X Layer `0xde07…1D84`）有漏洞：领取签名不绑定领取人，任何人都能换地址把剩下的份额领走。已经换成新合约 `TapeTapeRedPacket`，代码在 `contracts/`：

- 设计、已知局限：`contracts/README.md`
- 三轮独立审计的记录：`contracts/AUDIT.md`（最终没有阻断、高危、中危问题）
- 测试：`cd contracts && forge test`（102 条）

**部署（由合约主人亲自操作）**：

1. 在 `contracts/deploy/` 下运行 `python3 -m http.server 8735`。
2. 在装了钱包扩展的 Chrome 里打开 `http://localhost:8735`。
3. 按页面上的 1–7 步操作：连接钱包、选链、部署、核对、设置各代币最小金额、到 Sourcify 公开源码、复制结果。
4. BSC、X Layer 各部署一次。

**已部署**：

| 链 | 地址 | 费率 | 状态 |
|---|---|---|---|
| X Layer | `0x02e0d72021c301371255f2b9469783cd5290384c`（主人 `0x571d…af15`，OKB、BEM 最小 0.01） | 10‰ | 2026-11-23 部署，代码与审计版本一致，Sourcify 完全匹配，已填进客户端 |
| BSC | `0xc5319274ffc978ae02d4354bc89f04cdcda94703`（主人 `0x571d…af15`） | 10‰ | 2026-11-23 部署，代码与审计版本一致，已填进客户端 |

核对已部署合约：`cargo test -p tapetape-core deployed_contracts -- --ignored --nocapture`（只读）。

**部署后**：

1. 把两个地址填进 `core/crates/tapetape-core/src/chain.rs` 的 `BSC_PACKETS` / `XLAYER_PACKETS`。
2. 把 `max_packet_fee_rate` 设为部署时的费率（默认 BSC 0、X Layer 5）。
3. 重新编译内核和两端。

地址没填之前，这条链不支持红包和转账。

**注意**：

- 合约不可升级。主人能做的只有三件事：改费率（上限 50‰）、按代币设最小金额、提手续费。
- 主人调高费率后，旧版客户端会停发，提示用户更新 App，所以调费率要和客户端发版一起做。
- 一个群成员可以抢光整个红包（设计取舍，见 README），所以红包只适合发给信得过的群。

### 6. 广场

两端的广场页直接打开 OKX 的 X Layer 黑客松投票页 `https://web3.okx.com/zh-hans/xlayer/hackathon-voting`（iOS `ios/TapeTape/Square/SquareView.swift`，Android `android/app/src/main/java/org/tapetape/v2/ui/square/SquareScreen.kt` 的 `SQUARE_URL`）。原来的匿名留言板（`cf-msg-board`，后台 `msg-board.hivehubs.app`）已不再使用；`square/tapetape-square-web.zip` 是它的旧前端包，留作存档。

## 三、构建

```sh
# core（两端打包前都要先编）
core/scripts/build-ios.sh        # → core/out/ios/TapeTapeCore.xcframework + Swift 绑定
core/scripts/build-android.sh    # → core/out/android/jniLibs（4 个架构）+ Kotlin 绑定

# iOS
cd ios && xcodegen generate && open TapeTape.xcodeproj

# Android
cd android && ./gradlew assembleDebug          # 开发
cd android && ./gradlew bundleRelease          # 上架
```

需要：Rust（stable）、Xcode + xcodegen、Android SDK + NDK、cargo-ndk（脚本会自动装）。

## 四、测试

```sh
cd core
cargo test                                                         # 单元测试
cargo test -p tapetape-core live_ -- --ignored --nocapture             # 真实节点联测（单聊、群、备份、删除联系人）
cargo test -p tapetape-core chain_sim -- --ignored --nocapture         # BSC / X Layer 主网模拟（不发交易）
cargo test -p tapetape-core live_simulate_shop_open -- --ignored       # 开店主网模拟（不发交易）
```

两端的界面测试：iOS `ios/TapeTapeUITests/`，Android 见 `android/BUILD.md`。

## 五、要对用户讲清楚的

- 新版和旧版消息不互通，好友要都升级到新版才能聊天。
- 一个身份只能在一台设备上收消息。
- 陌生人在你接受之前只能发来一条。
- 没开大附件时，单条消息最大约 170 KB，语音最长 55 秒，视频基本发不出去。
- 收消息靠系统后台刷新，没有推送，锁屏后可能要等一段时间。
- 备份文件里有私钥。不设密码时用助记词才能恢复；设了密码就要记住密码。
- 红包和转账 7 天内没领完，发送方可以退回。
