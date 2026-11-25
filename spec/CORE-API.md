# TapeTape v2 Core API（M1）

## Deviations

- `CoreError`：UniFFI 的错误只能是 enum，实际是 `enum CoreError { Failed { message: String } }`（flat error）。Swift：`catch CoreError.Failed(let message)`；Kotlin：`catch (e: CoreException) { e.message }`（具体类 `CoreException.Failed`）。
- 约定补充（签名未变）：
  - `Message.error` 是原因代码，UI 自己翻译：`sender-not-allowed`（对方没加你）、`recipient-no-key`（对方没发布密钥）、`too-large`、`http-<状态码>`，其余为节点发件箱返回的原样代码。
  - `set_profile(name, avatar_path)`：`avatar_path = None` 表示去掉头像；要保留就传当前的 `AccountInfo.avatar_path`。
  - `add_account(mnemonic, node = None)`：先在各在线节点上找这个地址已发布过的密钥（导入旧账号时沿用原节点），找不到再随机选一台。
- 绑定产物：iOS `core/out/ios/TapeTapeCore.xcframework`（C 模块 `tapetape_coreFFI`）+ `core/out/ios/bindings/tapetape_core.swift`（加进 app target）；Android `core/out/android/jniLibs/{arm64-v8a,x86_64}/libtapetape_core.so` + `core/out/android/kotlin/uniffi/tapetape_core/tapetape_core.kt`（包名 `uniffi.tapetape_core`，依赖 `net.java.dev.jna:jna:5.15.0@aar`、`kotlinx-coroutines-core`）。

Rust crate `tapetape-core`，用 UniFFI proc-macro 导出。异步函数在 Swift 里是 `async`，在 Kotlin 里是 `suspend`。错误统一为 `CoreError { message: String }`，`message` 直接给用户看。之后的阶段只增不改。

## 对象

```rust
#[uniffi::export(callback_interface)]
pub trait EventListener: Send + Sync {
    fn on_event(&self, account_id: u32, event: Event);
}

#[derive(uniffi::Object)]
pub struct Core;                     // 进程内唯一

#[uniffi::export]
impl Core {
    #[uniffi::constructor]
    pub fn new(data_dir: String, listener: Box<dyn EventListener>) -> Result<Arc<Self>, CoreError>;

    pub fn accounts(&self) -> Vec<AccountInfo>;
    pub fn selected_account(&self) -> Option<u32>;
    pub fn select_account(&self, id: u32) -> Result<(), CoreError>;
    pub async fn nodes(&self) -> Result<Vec<String>, CoreError>;            // 在线节点主机名，如 relay1.taperelay.com
    pub fn generate_mnemonic(&self) -> String;                                // 12 词，UI 只显示一次
    pub async fn add_account(&self, mnemonic: String, node: Option<String>) -> Result<u32, CoreError>;  // node=None 自动选；新建和导入都走这里
    pub fn remove_account(&self, id: u32) -> Result<(), CoreError>;
    pub fn account(&self, id: u32) -> Result<Arc<Account>, CoreError>;
}

#[derive(uniffi::Object)]
pub struct Account;

#[uniffi::export]
impl Account {
    pub fn info(&self) -> AccountInfo;
    pub async fn set_profile(&self, name: String, avatar_path: Option<String>) -> Result<(), CoreError>;
    pub fn start(&self);                 // 开始收发（SSE + 发件队列），幂等
    pub fn stop(&self);
    pub fn connectivity(&self) -> Connectivity;

    pub fn chats(&self, query: Option<String>) -> Vec<ChatSummary>;          // 置顶在前，再按最后消息时间倒序；不含归档
    pub fn chat(&self, chat_id: u32) -> Result<Chat, CoreError>;
    pub fn messages(&self, chat_id: u32, before: Option<u32>, limit: u32) -> Vec<Message>;  // 时间正序
    pub async fn send_text(&self, chat_id: u32, text: String, reply_to: Option<u32>) -> Result<u32, CoreError>; // 先入库返回 id，后台发送
    pub fn mark_read(&self, chat_id: u32);
    pub fn set_draft(&self, chat_id: u32, text: Option<String>);

    pub fn invite_link(&self) -> String;                                      // PROTOCOL.md §6
    pub fn reset_invite(&self);
    pub async fn accept_invite(&self, link: String) -> Result<u32, CoreError>; // 返回 chat_id
    pub fn contacts(&self) -> Vec<Contact>;
}
```

## 记录

```rust
pub struct AccountInfo { id: u32, address: String /*0x…*/, account: String /*key:…*/, name: String, avatar_path: Option<String>, node: String }
pub struct Contact { id: u32, account: String, address: String, name: String, avatar_path: Option<String>, verified: bool }
pub struct ChatSummary { id: u32, name: String, avatar_path: Option<String>, is_group: bool, last_text: Option<String>, last_time: i64 /*ms*/, last_state: Option<MessageState>, unread: u32, pinned: bool, muted: bool, draft: Option<String> }
pub struct Chat { id: u32, name: String, avatar_path: Option<String>, is_group: bool, contact_id: Option<u32>, verified: bool }
pub struct Message { id: u32, chat_id: u32, from_contact: Option<u32> /*None=自己*/, sender_name: String, text: String, time: i64 /*ms*/, state: MessageState, error: Option<String>, reply_to: Option<u32>, kind: MessageKind }
pub enum MessageKind { Text, System }       // M2 起加 Image/Video/Voice/File/…
pub enum MessageState { Pending, Sent, Delivered, Read, Failed, Incoming, Seen }
pub enum Connectivity { NotConnected, Connecting, Connected }
pub enum Event { ChatListChanged, MessagesChanged { chat_id: u32 }, IncomingMessage { chat_id: u32, msg_id: u32 }, MessageStateChanged { chat_id: u32, msg_id: u32 }, ContactsChanged, ConnectivityChanged, Error { message: String } }
```

## 存储

`data_dir/accounts/<id>/db.sqlite`、`data_dir/accounts/<id>/blobs/`。助记词存在账号数据库里（和现在的 app 一样，靠系统沙盒保护）；备份文件的加密方案在 M3 定。

## M2（只增不改）

### Core

```rust
pub fn address_for_mnemonic(&self, mnemonic: String) -> Result<String, CoreError>;   // 导入确认页预览 0x 地址；12 或 24 词
pub fn max_attachment_size(&self) -> u64;                                            // 附件上限（字节），超过的消息直接 Failed("too-large")
```

### Account

```rust
pub fn message(&self, msg_id: u32) -> Result<Message, CoreError>;

// 媒体 / 名片 / 反应 / 转发 / 删除 / 重发 / 收藏
pub async fn send_media(&self, chat_id: u32, kind: MediaKind, path: String, text: Option<String>, reply_to: Option<u32>, duration_ms: Option<u32>) -> Result<u32, CoreError>; // 先拷进 blobs/，图片读宽高
pub async fn send_vcard(&self, chat_id: u32, contact_id: u32) -> Result<u32, CoreError>;
pub async fn react(&self, msg_id: u32, emoji: String) -> Result<(), CoreError>;     // emoji="" 撤销；每人一个反应
pub fn message_reactions(&self, msg_id: u32) -> Vec<ReactionBy>;                    // 「回应」名单
pub async fn forward(&self, msg_ids: Vec<u32>, to_chat_id: u32) -> Result<Vec<u32>, CoreError>;
pub fn delete_messages(&self, msg_ids: Vec<u32>);                                   // 只删本机
pub async fn resend(&self, msg_ids: Vec<u32>) -> Result<(), CoreError>;            // 只处理自己发的
pub fn set_saved(&self, msg_id: u32, saved: bool) -> Result<(), CoreError>;         // ★ 收藏 = 复制到「收藏的消息」
pub fn saved_messages_chat(&self) -> Result<u32, CoreError>;                        // 自己的会话，没有就建；消息只存本机
pub fn add_device_message(&self, text: String) -> Result<u32, CoreError>;           // 「设备消息」会话（只读），返回消息 id

// 会话操作
pub fn set_pinned(&self, chat_id: u32, pinned: bool) -> Result<(), CoreError>;
pub fn mute(&self, chat_id: u32, until: Option<i64>) -> Result<(), CoreError>;      // until=毫秒时间；None=永久
pub fn unmute(&self, chat_id: u32) -> Result<(), CoreError>;
pub fn set_archived(&self, chat_id: u32, archived: bool) -> Result<(), CoreError>;
pub fn delete_chat(&self, chat_id: u32) -> Result<(), CoreError>;
pub fn clear_chat(&self, chat_id: u32) -> Result<(), CoreError>;
pub fn archived_chats(&self) -> Vec<ChatSummary>;
pub fn mark_all_read(&self, archived: bool);                                        // archived=true 只标存档列表
pub fn accept_chat(&self, chat_id: u32) -> Result<(), CoreError>;                   // 接受联系人请求（加入允许名单）
pub async fn set_disappearing(&self, chat_id: u32, seconds: u32) -> Result<(), CoreError>; // 0=关；同步给对方
pub fn search_messages(&self, chat_id: Option<u32>, query: String) -> Vec<Message>; // 时间正序；None=全部会话
pub fn media_messages(&self, chat_id: u32, kinds: Vec<MessageKind>) -> Vec<Message>; // 时间正序
pub fn encryption_info(&self, chat_id: u32) -> Result<String, CoreError>;

// 联系人
pub fn contact(&self, contact_id: u32) -> Result<Contact, CoreError>;
pub fn create_chat(&self, account: String, name: String) -> Result<u32, CoreError>; // 名片「和 X 聊天？」、按地址开聊
pub fn chat_for_contact(&self, contact_id: u32) -> Result<u32, CoreError>;          // 资料页「发送消息」
pub fn set_contact_name(&self, contact_id: u32, name: String) -> Result<(), CoreError>; // 本地备注，空串=用对方昵称
pub fn block_contact(&self, contact_id: u32) -> Result<(), CoreError>;              // 节点也拉黑；会话不再显示
pub fn unblock_contact(&self, contact_id: u32) -> Result<(), CoreError>;
pub fn blocked_contacts(&self) -> Vec<Contact>;

// 自己的资料
pub async fn set_bio(&self, bio: String) -> Result<(), CoreError>;                  // 和昵称、头像一起用 profile 信封发给联系人
```

### 记录（新增字段）

```rust
pub enum MessageKind { Text, System, Image, Gif, Sticker, Video, Voice, Audio, File, Vcard }
pub enum MediaKind { Image, Gif, Sticker, Video, Voice, Audio, File }
pub struct Reaction { emoji: String, count: u32, mine: bool }
pub struct ReactionBy { contact_id: Option<u32> /*None=自己*/, name: String, emoji: String }
pub struct Vcard { name: String, account: String, address: String }
pub enum SystemMessage { TimerChanged { seconds: u32 } }     // 文案由 UI 按 from_contact 拼

Message     += file_path: Option<String>, file_name: Option<String>, mime: Option<String>, size: Option<u64>,
               width: Option<u32>, height: Option<u32>, duration_ms: Option<u32>, vcard: Option<Vcard>,
               reactions: Vec<Reaction>, forwarded: bool, saved: bool, original_id: Option<u32> /*收藏副本指向原消息*/,
               expires_at: Option<i64> /*ms*/, system: Option<SystemMessage>
Chat        += is_self: bool, is_device: bool, is_request: bool, can_send: bool, archived: bool, pinned: bool,
               muted: bool, muted_until: Option<i64> /*None=未静音或永久*/, ephemeral_seconds: u32, blocked: bool
ChatSummary += archived: bool, is_self: bool, is_device: bool, is_request: bool, last_kind: Option<MessageKind>,
               last_from_me: bool, last_seen: Option<i64> /*1:1 对方最近活动 ms*/
Contact     += bio: String, last_seen: Option<i64>, blocked: bool
AccountInfo += bio: String
Event       += IncomingReaction { chat_id: u32, msg_id: u32 }
```

### 约定

- 会话名：`is_self` 的名字是 "Saved Messages"、`is_device` 的是 "Device Messages"，UI 按这两个标志自己翻译。
- 联系人请求：陌生人来的会话 `is_request=true`；`accept_chat` 后变成正常会话，自己发消息也算接受；`block_contact` 后会话从列表消失。
- 定时消息：自己发的从发出起计时，收到的从 `mark_read` 起计时；到期后 core 删除并发 `MessagesChanged`。对方改了定时器会插入一条 `system = TimerChanged` 消息。
- 附件上限是 `max_attachment_size()`（大附件 0x23 帧上线后再调大）；超过的消息照样入库，状态直接 `Failed`，`error = "too-large"`。
- `delete_messages` 只删本机；没有编辑、没有「为所有人删除」。
- 「设备消息」会话里的消息 `from_contact = None` 但 `state = Incoming`，按收到的消息显示。收藏副本（`original_id` 有值）保留原发送者的 `from_contact`。
- 当前 `max_attachment_size()` = 170 KiB（174080 字节）。开启大附件后用 `Account.max_attachment_size()`（见「大附件」）。
- 导入助记词接受 12 或 24 词（iOS 旧版行为）；新建仍是 12 词。
- 收到的媒体文件存在 `blobs/` 里，`file_path` 是绝对路径；删除最后一条引用它的消息时文件一并删除。

## M3（只增不改）

### Core

```rust
pub fn move_account_to_top(&self, id: u32) -> Result<(), CoreError>;     // accounts() 按用户顺序返回（新账号排最后）
pub fn backup_protection(&self, path: String) -> Result<BackupProtection, CoreError>; // 恢复前先问：要口令还是助记词
pub async fn import_backup(&self, path: String, secret: String) -> Result<u32, CoreError>; // 恢复成新账号（不自动选中）；secret = 口令或助记词
pub async fn fetch_all(&self) -> FetchResult;                            // 后台刷新：每个账号收一次 + 发一次
```

### Account

```rust
// 群（PROTOCOL §4.2、§6）
pub async fn create_group(&self, name: String, avatar_path: Option<String>, members: Vec<u32>) -> Result<u32, CoreError>; // members = contact_id，不含自己，可以为空
pub fn group_info(&self, chat_id: u32) -> Result<GroupInfo, CoreError>;
pub async fn set_group_name(&self, chat_id: u32, name: String) -> Result<(), CoreError>;
pub async fn set_group_avatar(&self, chat_id: u32, avatar_path: Option<String>) -> Result<(), CoreError>; // None = 去掉
pub async fn add_group_members(&self, chat_id: u32, contact_ids: Vec<u32>) -> Result<(), CoreError>;
pub async fn remove_group_member(&self, chat_id: u32, contact_id: u32) -> Result<(), CoreError>;
pub async fn leave_group(&self, chat_id: u32) -> Result<(), CoreError>;
pub fn group_invite_link(&self, chat_id: u32) -> Result<String, CoreError>;   // 群二维码 / 链接；accept_invite 认得出，返回群的 chat_id
pub fn reset_group_invite(&self, chat_id: u32) -> Result<(), CoreError>;
pub fn message_deliveries(&self, msg_id: u32) -> Vec<Delivery>;               // 自己发的消息每个收件人的状态（群里列失败原因用）

// 设置（每个账号一份）
pub fn settings(&self) -> Settings;
pub fn set_settings(&self, settings: Settings);
pub fn count_messages_older_than(&self, seconds: u32) -> u32;                 // 「删除旧消息」确认框里的条数
pub fn set_account_muted(&self, muted: bool);                                 // 账号切换器「通知静音」= 设置里「通知」开关
pub fn set_account_tag(&self, tag: String);                                   // 私密标签，空串 = 去掉

// 助记词 / 备份
pub fn mnemonic(&self) -> String;                                             // UI 先做设备验证
pub async fn export_backup(&self, dest_dir: String, passphrase: Option<String>) -> Result<String, CoreError>; // 返回备份文件完整路径

// 后台 / 分享扩展
pub async fn fetch_once(&self) -> Result<FetchResult, CoreError>;             // 收一次收件箱 + 发一次待发队列，不需要 start()
pub async fn send_pending(&self) -> Result<(), CoreError>;                    // 只发待发队列（分享扩展发完消息后调）
```

### 记录（新增）

```rust
pub struct GroupMember { contact_id: Option<u32> /*None=自己*/, name: String, account: String, address: String,
                         avatar_path: Option<String>, admin: bool }
pub struct GroupInfo { chat_id: u32, name: String, avatar_path: Option<String>, members: Vec<GroupMember> /*自己排第一*/,
                       is_member: bool, is_admin: bool }
pub struct Delivery { contact_id: u32, name: String, state: MessageState, error: Option<String> }
pub struct Settings { read_receipts: bool, media_quality: MediaQuality, auto_download: AutoDownload,
                      delete_device_after: Option<u32> /*秒；None=从不*/, mentions: bool }
pub enum MediaQuality { Balanced, Worse }
pub enum AutoDownload { All, Kib160, Kib640, Mib5, Mib25 }
pub struct Notification { account_id: u32, chat_id: u32, msg_id: u32, chat_name: String, sender_name: String,
                          is_group: bool, kind: MessageKind, text: String, reaction: Option<String>,
                          muted: bool, mention: bool }
pub struct FetchResult { new_messages: u32, notifications: Vec<Notification> }
pub enum BackupProtection { Passphrase, RecoveryPhrase }

SystemMessage += GroupCreated { name: String }, GroupNameChanged { name: String }, GroupAvatarChanged,
                 MemberAdded { contact_id: Option<u32>, name: String }, MemberRemoved { contact_id: Option<u32>, name: String },
                 MemberLeft
AccountInfo   += muted: bool, tag: String, unread: u32
ChatSummary   += last_sender: Option<String>
Chat          += member_count: u32
Event         += Notification { notification: Notification }
```

### 约定

- **群**
  - `Chat.is_group` / `ChatSummary.is_group` 为真；群会话的 `contact_id = None`。`can_send` = 自己还是成员且已经收到群状态（扫码入群后、群主的 `group_state` 到之前为 false）。`member_count` 含自己。
  - 所有成员都是管理员（旧版行为：谁都能改名、改头像、加人、踢人）；`GroupMember.admin` / `is_admin` 照实返回。
  - 系统消息（`kind = System`）的文案由 UI 按 `system` 和 `from_contact`（做这件事的人，None = 自己）拼：
    - `GroupCreated`：只在建群的人那边出现。
    - `MemberAdded` / `MemberRemoved`：被加 / 被踢的人，`contact_id = None` 表示是自己（「X 把你加进了群」「你被 X 移出了群」）。
    - `MemberLeft`：`from_contact` 退群。
    - `GroupNameChanged`、`GroupAvatarChanged`。
  - 克隆群：UI 用 `group_info` 的名字、头像、成员预填「新建群组」，再调 `create_group`。
  - 私下回复：`chat_for_contact(发送者)` 拿到 1:1 会话，再 `send_text(那个会话, 文字, reply_to = 群里那条消息的 id)`；`reply_to` 可以引用别的会话的消息。
  - 陌生人把你拉进群：群会话 `is_request = true`，`accept_chat` 后才把成员加进允许名单；在群里发消息也算接受。
- **群消息的投递状态**：给每个成员各发一份。`Message.state` 汇总：有人失败 → `Failed`（`error` 是第一个失败原因，完整名单用 `message_deliveries`）；还有没交给节点的 → `Pending`；有人回了已读 → `Read`；全部送达 → `Delivered`；否则 `Sent`。`resend` 会给全体再发一遍（收到过的人按信封 id 去重）。
- **设置**：core 只存需要 core 知道或要跟着备份走的：
  - `read_receipts`：关了就不发已读，也不处理别人的已读。
  - `media_quality`、`auto_download`：只保存，UI 压缩图片时读；附件目前都 ≤ 170 KiB、总是自动下载。
  - `delete_device_after`：core 每分钟删一次早于这个时间的消息（「收藏的消息」和「设备消息」不删）。「从节点删除」固定是「下载后立即」（TapeRelay 收件箱读完即删），没有设置。
  - `mentions`：只保存，配合 `Notification.mention` 用。
  - 主题、背景、铃声、振动、LED、通知显示内容、Enter 发送、聊天音是设备级的界面偏好，UI 自己存。
- **通知**：每条新消息、以及别人对我的消息的反应，都会发 `Event::Notification`（同时照旧发 `IncomingMessage` / `IncomingReaction`）。`muted` = 账号静音或会话静音；`mention` = 回复了我的消息或对我的消息的反应。UI 规则：`!muted || (mention && settings.mentions)` 才弹。`text` 是文字或媒体说明，媒体类型按 `kind` 由 UI 翻译；反应时 `reaction` 是表情、`text` 是被反应的那条消息。
- **后台**：iOS BGAppRefresh / 通知扩展、Android WorkManager 调 `fetch_all()`（或单个账号 `fetch_once()`），用返回的 `notifications` 弹通知；`new_messages` 是新消息条数。
- **助记词**：`mnemonic()` 直接返回，UI 负责设备验证和定时清剪贴板；core 不提供私钥导出（旧版「复制私钥」是风险项，不做）。
- **备份**
  - 文件名 `tapetape-backup-<0x地址>-<YYYY-MM-DD>.tapetapebak`，内容是 tar（数据库快照 + `blobs/`，助记词在数据库里），整体加密。
  - 格式：`TAPETAP1`（8 字节）+ 模式（1 字节：1 = 口令，2 = 助记词）+ salt（16）+ nonce（19）+ 地址（42 字节 ASCII `0x…`），之后是 XChaCha20-Poly1305 STREAM（BE32，64 KiB 一块，头部作为每块的 AAD）。
  - 有口令：密钥 = scrypt(口令, salt, N=2^15, r=8, p=1)。没口令（和旧版一样，导出时不问口令）：密钥 = SHA256("tapetape/backup/v1" ‖ 账号私钥)，恢复时让用户输入这个账号的助记词（12 或 24 词）。
  - 恢复：`backup_protection(path)` 决定提示「输入备份口令」还是「输入助记词」，再 `import_backup`。口令不对 → 「Wrong passphrase or damaged backup」，助记词不对 → 「This recovery phrase does not belong to this backup」；账号已存在 → 「This account has already been added」。
  - 同一个账号不要在两台设备上同时用（两边会抢同一个收件箱）。
  - 数据库里的文件路径从 M3 起都存相对路径（`blobs/…`），API 里照旧给绝对路径；所以恢复到别的设备、iOS 容器路径变化都不受影响。
- **多进程（iOS 分享扩展 / 通知扩展）**
  - `data_dir` 放在 App Group 容器里；主 app 和扩展各自 `Core::new(同一个目录)`。
  - SQLite 是 WAL + 5 秒 busy timeout，多个进程同时读写是安全的；core 不长时间持有事务。
  - 扩展里不要调 `start()`：分享扩展 `send_text` / `send_media` 后调 `send_pending()`；通知扩展调 `fetch_once()`。
  - 两个进程同时发同一条消息，对方按信封 id 去重。
  - iOS 主 app 进入后台前调 `stop()`，回到前台再 `start()`。
- **账号切换器**：`AccountInfo.muted` / `tag` / `unread`（未读，不含静音会话）；删除账号用 `remove_account`。

## M4（只增不改）

### Core

```rust
pub fn wallet_tokens(&self) -> Vec<Token>;     // 固定顺序：X Layer（OKB、BEM）在前，BSC（BNB、BEM、USDT）在后
```

### Account

```rust
// 钱包
pub fn wallet_address(&self) -> String;                                           // 「收款」二维码 = 这个 0x 地址（两条链同一个）
pub async fn balances(&self) -> Vec<Balance>;                                     // 所有代币，顺序同 wallet_tokens；查不到的 amount = None（显示 "--"）
pub async fn estimate_send(&self, token_id: String, to: String, amount: String) -> Result<SendEstimate, CoreError>;  // 提款页
pub async fn send_token(&self, token_id: String, to: String, amount: String) -> Result<String, CoreError>;           // 广播，返回交易哈希
pub async fn tx_status(&self, chain_id: u64, tx_hash: String) -> Result<TxStatus, CoreError>;

// 红包（只在群里）/ 转账（只在 1:1）
pub async fn estimate_packet(&self, token_id: String, amount: String, count: u32) -> Result<SendEstimate, CoreError>;
pub async fn send_red_packet(&self, chat_id: u32, token_id: String, amount: String, count: u32, greeting: String) -> Result<u32, CoreError>; // 等上链确认，返回 msg_id（10 秒到几分钟）
pub async fn send_transfer(&self, chat_id: u32, token_id: String, amount: String, note: String) -> Result<u32, CoreError>;
pub async fn packet_status(&self, msg_id: u32) -> Result<PacketStatus, CoreError>;   // 读链刷新，同时更新 Message.packet.status
pub async fn claim_packet(&self, msg_id: u32) -> Result<String, CoreError>;          // 等上链确认，返回领到的金额
pub async fn refund_packet(&self, msg_id: u32) -> Result<String, CoreError>;         // 过期后发送者退回剩余，返回交易哈希

// 链上小店（X Layer），对应旧 JSON-RPC 的 shop_info / shop_open / shop_publish / shop_remove，网页桥协议不变（SHOP-SPEC）
pub async fn shop_info(&self) -> Result<ShopInfo, CoreError>;
pub async fn shop_open(&self) -> Result<ShopInfo, CoreError>;
pub async fn shop_publish(&self, input: ProductInput) -> Result<Product, CoreError>;
pub async fn shop_remove(&self, product_id: String) -> Result<(), CoreError>;
```

### 记录（新增）

```rust
pub struct Token { id: String /*"56:USDT"*/, chain_id: u64, chain_name: String /*"X Layer" | "BNB Smart Chain"*/, symbol: String,
                   address: Option<String> /*None=原生币*/, decimals: u8, packets: bool /*能发红包/转账*/ }
pub struct Balance { token_id: String, amount: Option<String> }
pub struct SendEstimate { amount: String /*对方实际到账*/, fee: String, fee_symbol: String /*原生币*/, balance: String /*该代币余额*/,
                          max: String /*「全部」按钮填的值*/, error: Option<String> }
pub enum TxStatus { Pending, Confirmed, Failed, NotFound }
pub enum PacketKind { RedPacket, Transfer }
pub enum PacketState { Creating, Open, Finished, Expired, Refunded, Failed }
pub struct ClaimRecord { address: String, amount: String, contact_id: Option<u32>, me: bool }
pub struct PacketStatus { state: PacketState, claimed_count: u32, total_count: u32, claimed_amount: String, my_amount: Option<String>,
                          can_claim: bool, can_refund: bool, expires_at: Option<i64> /*ms*/, records: Vec<ClaimRecord>, checked_at: i64 }
pub struct Packet { kind: PacketKind, packet_id: String, chain_id: u64, token_id: Option<String>, symbol: String, amount: String /*总额*/,
                    count: u32, text: String /*祝福语或转账备注*/, tx: String, creator: String /*0x*/, mine: bool, status: PacketStatus /*上次查询的结果*/ }
pub struct ShopInfo { address: String, balance: String /*OKB*/, open_cost: String /*OKB；已开好为 "0"*/, ready: bool,
                      name: Option<String>, app_url: Option<String>, share_url: Option<String> }
pub struct ProductInput { description: String, original_price: u64 /*分*/, discount_price: u64, images: Vec<String> /*base64 JPEG*/ }
pub struct Product { id: String, description: String, original_price: u64, discount_price: u64, images: Vec<String>, created_at: i64 /*秒*/ }

MessageKind   += RedPacket, Transfer
Message       += packet: Option<Packet>              // kind 是 RedPacket / Transfer 时有值；text = 祝福语 / 备注
SystemMessage += PacketClaimed { claimer: Option<u32>, owner: Option<u32>, packet_msg_id: Option<u32> }  // None = 自己；「X 领取了 Y 的红包」
```

### 约定

- 金额一律是十进制字符串（"1.5"），按代币精度精确换算，不丢精度；UI 自己按「最多 5 位小数、手续费 8 位」显示。
- 链和代币都来自 core 里的一张配置表（链 ID、RPC、原生币、ERC-20 地址和精度、红包合约地址）。红包 / 转账合约也在这张表里，X Layer 和 BSC 都有（`Token.packets`）。
- **提款**：`estimate_send` 的 `amount` 就是对方到账金额（不扣手续费；手续费用原生币另付，修掉旧版「BNB 到账 = 金额 − gas」的显示错误）。原生币的 `max` = 余额 − 手续费。`error` 是代码，UI 翻译：
  `invalid-address`、`invalid-amount`、`invalid-count`、`insufficient-balance`、`insufficient-gas`（原生币不够付手续费）、`amount-too-small`（红包低于合约最小额或少于个数）。`send_token` 失败的 `message` 是同样的代码或节点返回的原文。`send_token` 广播后立刻返回哈希，用 `tx_status` 轮询。
- `balances` 和 `estimate_*` 都读链，约 1 秒；`Token.id` 是 `"<chain_id>:<symbol>"`。BEM 精度 8，其余 18。
- **红包 / 转账**（PROTOCOL §5）：
  1. `send_*` 先在本地插入一条消息（`packet.status.state = Creating`，不发出去，卡片显示「发送中」），然后：ERC-20 授权不够就广播一笔正好等于金额的 `approve` 并等确认 → 广播 `createRedPacket` 并等确认 → 核对链上数据 → 状态改为 `Open` 并把消息发出去。
  2. 失败：状态改为 `Failed`，消息 `state = Failed`、`error` = 原因，函数返回同样的错误。`Creating` / `Failed` 的消息不能重发、不能转发（所有红包 / 转账都不能转发）。
  3. app 中途被杀：下次对这条消息调 `packet_status` 时 core 自己补完（链上已创建 → 发出去；交易还在等 → 保持 `Creating`；否则 `Failed`）。
  4. 领取：`claim_packet` 广播 `claimRedPacket(id, signature)`，等确认，读出领到的金额，再发 `redpacket_claim` / `transfer_claim`。红包的领取在群里加一条 `PacketClaimed` 系统消息（对方领取时也会收到）；转账的领取不显示，只刷新卡片。
  5. `packet_status` 每次都读链并核对卡片（创建者、代币、总额、个数不符 → `Failed`，显示「无效」）；结果存进 `Message.packet.status`，有变化时发 `MessagesChanged`。卡片先用存下的状态显示，出现在屏幕上时再调它刷新。
  6. 状态：`Open`（可领）、`Finished`（领完）、`Expired`（7 天到了还有剩余；发送者 `can_refund`）、`Refunded`（已退回）。`can_claim` = `Open` 且自己没领过；转账的发送者不能领自己的转账，红包的发送者可以领自己的红包（合约允许）。
  7. 过期：创建 7 天后（604800 秒，按链上时间）发送者可以 `refund_packet` 退回剩余；过期后客户端不再允许领取。
  8. 手续费：领取和退回由操作的人用原生币付（BSC 上是 BNB）；不够时返回 `insufficient-gas`。
  9. 错误代码（`CoreError.message`）：`invalid-amount`、`invalid-count`、`amount-too-small`、`insufficient-balance`、`insufficient-gas`、`already-claimed`、`finished`、`expired`、`refunded`、`not-claimable`、`not-refundable`、`tx-failed`（上链但回滚）、`tx-timeout`（3 分钟没确认，稍后 `packet_status` 会补完），其余是节点原文。
- **链上小店**：和旧 core 行为一致（同一个网页 `index.html`、同样的文件布局和网页桥），只是从 JSON-RPC 换成这四个方法。
- 测试从不发真实交易；链上逻辑用 ABI 编码单元测试 + `eth_simulateV1`（余额覆盖）对 BSC / X Layer 主网状态模拟。

## 大附件（只增不改）

### Account

```rust
pub fn relay_files_backend(&self) -> String;                          // 空 = 关闭
pub fn set_relay_files_backend(&self, url: String) -> Result<(), CoreError>;   // 例如 "https://tapetape.org"；空串关闭
pub fn max_attachment_size(&self) -> u64;                             // 开启 = 100 MiB（104857600），关闭 = 170 KiB；UI 用这个，不再用 Core 上的同名方法
pub fn download(&self, msg_id: u32) -> Result<(), CoreError>;         // 开始 / 重试下载
```

### 记录（新增）

```rust
pub enum DownloadState { Available /*没下载，显示「下载」*/, Downloading, Failed /*显示「下载失败」，点了再调 download*/ }
Message += download: Option<DownloadState>       // None = 文件已在本地（或不是附件）
Event   += TransferProgress { msg_id: u32, done: u64, total: u64 }   // 上传和下载都发，字节数；每 1 MiB 左右一次
```

### 约定

- `relay_files_backend` 按账号保存，默认空（关闭）：超过 170 KiB 的附件照旧 `Failed("too-large")`。
- 开启后，超过 170 KiB、不超过 100 MiB 的附件：消息先入库（`Pending`），core 在后台加密上传（群 = 对全体成员加密），期间发 `TransferProgress`；传完才把消息发出去，之后状态照常变化。上传失败消息 `Failed`，`error` 是 `quota-exceeded`（后台每日额度用完）、`not-configured`（后台没配好）、`upload-failed`（网络等）或后台返回的其他代码；可以 `resend`（重新上传）。
- 收到大附件：消息马上出现，`file_path = None`，`size`/`width`/`height`/`duration_ms`/`file_name` 已有，`download` 有值。大小不超过「自动下载」设置（`Settings.auto_download`，默认全部）就自动开始下载，否则 `Available`，用户点了调 `download(msg_id)`。下载完成发 `MessagesChanged`，`file_path` 有值、`download = None`。
- 还没下载的附件不能转发（`forward` 会跳过）。
- 协议见 PROTOCOL §3.1。测试用本地假后台 + 假中继，不需要 `TAPERELAY_KEY`。

## 联系人补充（只增不改）

### Account

```rust
pub fn shared_chats(&self, contact_id: u32) -> Vec<ChatSummary>;   // 这个联系人所在的群（含已归档），排序同 chats()
pub fn delete_contact(&self, contact_id: u32) -> Result<(), CoreError>;
```

### 约定

- `delete_contact`（同旧 app）：联系人从 `contacts()` 里消失，同时移出中继的允许名单（之后对方的消息会当作陌生人请求）、取消「已验证」。和他的单聊、消息、群成员身份都保留，要删聊天由 UI 另调 `delete_chat`。
- 之后重新加他（扫邀请、`create_chat`、接受他的请求）就恢复成正常联系人。

## 代理（只增不改）

### Core（全局，所有账号共用；建号前也能设）

```rust
pub fn proxy_settings(&self) -> ProxySettings;
pub fn parse_proxy(&self, link: String) -> Result<ProxyInfo, CoreError>;      // 不是代理链接 → Err("invalid-proxy")；扫码 / 点链接时先用它判断
pub fn add_proxy(&self, link: String) -> Result<ProxySettings, CoreError>;    // 「使用代理」：加到列表最前、设为当前、打开开关
pub fn select_proxy(&self, url: String) -> Result<ProxySettings, CoreError>;  // 点列表里的一行：设为当前并打开
pub fn remove_proxy(&self, url: String) -> Result<ProxySettings, CoreError>;  // 删掉当前的会关掉开关
pub fn set_proxy_enabled(&self, enabled: bool) -> Result<ProxySettings, CoreError>;  // 没有当前的就用列表第一个；列表空时保持关
```

### 记录（新增）

```rust
pub enum ProxyKind { Http, Https, Socks5, Shadowsocks }
pub struct ProxyInfo { url: String /*规范化后的链接，分享二维码就用它*/, kind: ProxyKind, host: String, port: u16 }
pub struct ProxySettings { enabled: bool, active: Option<String> /*url*/, proxies: Vec<ProxyInfo> /*最近加的在前*/ }
```

### 约定

- 接受的链接：`socks5://[user:pass@]host[:port]`（也认 `socks5h://`，默认端口 1080）、`http://` / `https://host[:port]`（不能带路径或参数）、`ss://`（SIP002 `ss://base64(method:password)@host:port#名字` 和旧式 `ss://base64(method:password@host:port)`，支持 AEAD 和 2022 加密方式）、`tg://socks?server=&port=&user=&pass=`、`https://t.me/socks?...`。其他一律 `invalid-proxy`（UI 显示「无效或不支持的代理」）。
- 打开后 core 的所有网络请求都走当前代理：中继 API、SSE 推送、密钥查询、节点列表、链上 RPC、附件后台和对象服务器。SOCKS5 由代理端解析域名。Shadowsocks 在 core 内部起一个只监听 127.0.0.1 的本地转发。
- 改代理设置后，所有已启动账号立即断开重连；代理不通时账号的 `connectivity()` 停在 `Connecting` / `NotConnected` 并照常发 `ConnectivityChanged`，代理列表里当前那一行的状态就用当前账号的 connectivity 显示。
- 保存在数据目录的 `proxy.json`，不进备份。
- 备份文件名里的日期改用本地日期（`tapetape-backup-<地址>-<本地 YYYY-MM-DD>.tapetapebak`）。

## 群改名补充（只增不改）

```rust
SystemMessage::GroupNameChanged { name: String, old_name: String }   // 新增 old_name：改名前的群名，UI 显示「X 把群名从「旧」改为「新」」
```

- 本次之前存下的改名消息没有旧名，`old_name` 为空串；UI 遇到空串就只显示新名字。

## 安全加固（只增不改）

```rust
// 新增最后一个参数，默认 None（Swift / Kotlin 老调用不用改）。传估算页显示给用户的 SendEstimate.fee 原样字符串
pub async fn send_token(&self, token_id: String, to: String, amount: String, confirmed_fee: Option<String> = None) -> Result<String, CoreError>;
pub async fn send_red_packet(&self, chat_id: u32, token_id: String, amount: String, count: u32, greeting: String, confirmed_fee: Option<String> = None) -> Result<u32, CoreError>;
pub async fn send_transfer(&self, chat_id: u32, token_id: String, amount: String, note: String, confirmed_fee: Option<String> = None) -> Result<u32, CoreError>;
pub fn pending_sends(&self) -> Result<Vec<WalletTx>, CoreError>;   // 已签名、还没确认结果的提款（app 被杀后提款页用它恢复「处理中」）

pub struct WalletTx { chain_id: u64, token_id: String, to: String, amount: String, tx_hash: String, created_at: i64 /*ms*/ }
```

- **手续费上限**：发送时 core 重新估算；新手续费超过 `confirmed_fee` 的 1.5 倍就不发，返回 `fee-changed`（UI 重新调 `estimate_*` 让用户再确认）。`confirmed_fee` 解析不了返回 `invalid-fee`。
- **交易哈希先落盘**：core 在本地签名、算出哈希（keccak(raw)）并先存下来（红包 / 转账存进卡片的 `tx`，提款存进 `pending_sends`），再广播。广播报错时 core 用哈希查链：节点已收到就当成功（返回哈希 / 继续等确认），查不到才返回失败；查链本身也失败就当作「处理中」返回哈希，之后由 `tx_status` / `packet_status` 定结果。
- `pending_sends` 的记录在 `tx_status` 查到 `Confirmed` / `Failed` 时删除；`NotFound` 且发出超过 10 分钟也删除（视为没发出去）。广播明确失败的记录立即删除。
- **领取通知**：收到别人的 `redpacket_claim` 后先用 `getClaimRecords` 核对对方地址确实领过，核对通过才加 `PacketClaimed` 系统消息；核对不了（链上没有、RPC 不通）就不显示。卡片本身照常刷新。
- **收消息大小**：内联附件（消息附件、群头像、个人头像）超过 170 KiB 的消息整条丢弃；解压上限 4 + 128 KiB + 170 KiB。设置个人头像 / 群头像时图片超过 170 KiB 返回 `too-large`（UI 先压缩）。
- **大附件下载**：只从当前账号 `relay_files_backend` 的 `/api/relay/sign` 服务器列表里的服务器下载，引用里的其他服务器一律不连。没配置后台时大附件不自动下载（`Available`），点下载会失败（`Failed`）。
- **备份恢复**：备份包里只接受普通文件和目录，有链接等其他类型的条目整个备份拒绝导入。
- Shadowsocks 本地转发每次启用都用随机用户名密码，本机其他程序不能借用。
