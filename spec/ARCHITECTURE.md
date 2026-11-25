# TapeTape v2 架构与实施

## 1. 决定

| 项 | 决定 |
|---|---|
| 结构 | 自写 Rust core + 原生 iOS（SwiftUI）+ 原生 Android（Jetpack Compose） |
| 接口 | UniFFI 生成 Swift / Kotlin 绑定（不手写 C FFI、JNI） |
| 消息格式 | 自定义 JSON 信封（`PROTOCOL.md`），不兼容 TapeTape / TapeTape Relay |
| 开源协议 | MIT（见 `LICENSE`） |
| 默认链 | X Layer；默认资产 OKB、BEM |
| 包名 | 沿用 `org.tapetape.relay`（待定） |

## 1.1 范围（2026-11-20 Hao 确认）

- 外观、流程与上一代 app 保持一致，各端对照各自的上一代（含导航结构、深色模式、相机/图片编辑器、滑动操作等差异）。
- **只做用户现在看得到的功能**。不做：视频通话、广播列表、位置共享、小程序、第二设备、消息详情、panic 集成、iOS 小组件。
- **修掉旧 bug，外观流程不变**：红包、转账真正上链并实现到期退回；iOS 通讯录点联系人进入聊天；Android 提现金额、复制私钥、深色底栏图标、空列表提示；写死的中英文改走翻译；助记词在设置里可查看/导出（需验证身份）。

## 2. 净室规则（写代码的 AI 都要遵守）

- 代码全部独立编写，不参考上一代客户端的第三方代码。
- 依据只有：本目录的规格（`PROTOCOL.md`、`CORE-API.md`）、TapeRelay 官方文档和客户端库、我们自己写的代码。
- 直接复用的只有我们自己写的部分：TapeRelay 协议库（`core/crates/taperelay`：加密、EIP-712、交易签名）、链上小店、Android 变声器。
- 图标用谷歌 Material Design 图标（Apache 2.0），其余图片、音效由我们自己制作，见仓库根目录的第三方说明。

## 3. 目录

```
tapetape-v2/
  spec/        规格（本文件、PROTOCOL.md、CORE-API.md）
  core/        Rust workspace：tapetape-core（主库）、taperelay（TapeRelay 协议库）
  ios/         SwiftUI app
  android/     Compose app
```

## 4. Core 模块

| 模块 | 职责 |
|---|---|
| `account` | 助记词创建/导入、派生私钥与 x25519、多账号 |
| `store` | SQLite（rusqlite）：联系人、会话、消息、群、配置、待发队列；文件放 `blobs/` |
| `transport` | TapeRelay：发件、SSE + 拉取收件箱、发件箱状态、允许名单、发布密钥、大附件上传下载 |
| `protocol` | 信封编解码、分帧、分片重组、去重 |
| `chat` | 单聊、群、草稿、已读、反应、编辑、删除、置顶、归档、静音 |
| `invite` | 邀请链接/二维码、握手 |
| `wallet` | X Layer / BSC 余额（OKB、BEM、USDT…）、转账、红包合约、交易签名 |
| `shop` | 链上小店（从现有 `shop.rs` 移植） |
| `backup` | 整个账号导出为加密文件、导入 |
| `events` | 向 UI 推事件：新消息、状态变化、会话变化、连接状态 |

对 UI 的接口：同步查询（会话列表、消息列表、联系人）+ 异步动作（发送、开店、转账）+ 一个事件回调。

## 5. 收消息与通知

- 前台：SSE 实时收。
- 后台：iOS 用 BGAppRefresh 定时拉，Android 用 WorkManager 定时拉（可选前台服务）。
- 新消息发本地通知。TapeRelay 目前没有推送，后台延迟由系统调度决定。

## 6. 阶段

| 阶段 | 内容 | 验收 |
|---|---|---|
| M0 规格 | `PROTOCOL.md`、`CORE-API.md`、本文件 | Hao 确认 |
| M1 骨架 | core：账号、存储、传输、单聊文字；UniFFI；两端：注册/导入、会话列表、聊天页、邀请链接加好友 | 两台模拟器互发文字 |
| M2 媒体 | 图片、视频、文件、语音（含变声器）、大附件、回复、转发、删除、编辑、反应、已读 | 两端互发各类消息 |
| M3 群与资料 | 群全流程、头像昵称、联系人页、设置、备份恢复、多语言、深色模式 | 三台设备群聊 |
| M4 钱包 | X Layer 钱包（OKB、BEM）、收款转账、红包（等合约）、链上小店 | 主网小额实测 |
| M5 验收 | 逐页走查、真机测试、打包签名 | 可上架 |

每个阶段由多个 agent 并行：core 一路，iOS 一路，Android 一路，阶段末两台模拟器端到端联调。
