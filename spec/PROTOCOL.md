# TapeTape v2 消息协议

版本 1。传输用 TapeRelay 用户身份之间的消息；消息内容是本文定义的 JSON 信封，不再用 MIME。和上一代 TapeTape / TapeTape Relay 不互通。

## 1. 身份与密钥

| 项 | 规则 |
|---|---|
| 钱包 | BIP-39 助记词（12 词），路径 `m/44'/60'/0'/0/0` 得到 secp256k1 私钥 `sk`、地址 `0x…`。同一个地址用于 X Layer、BSC 等 EVM 链 |
| 消息身份 | `key:<0x地址小写>@<通信节点主机>:4246`，通信节点在注册时直接读链选取：X Layer 上的链上网关注册表 TapeRelayDirectory（`0x45e1a892ec4b9db3f0206e4b8c1f09f7a8f67a5b`）列出的、质押合约 `inGoodStanding` 为真的运营方的 `主机:4246`，再要求通信节点自己的 `/v1/info` 写的运营方编号（`operator.ids["eip155:196"]`）与链上一致、并支持 `self-id`，访问得到即在线。不经过任何中心目录服务（core `directory.rs`） |
| 加密密钥 | x25519 私钥 = `SHA256("tapetape/x25519/v1" ‖ sk)`；公钥用 TapeRelay「发布自己的密钥」接口发布，对方按身份读取 |
| 单设备 | 一个身份只在一台设备上收消息（通信节点上的消息取走即删） |

## 2. 传输层（TapeRelay）

- 发：`POST /v1/outbox`，正文是 TapeRelay inline 密封消息（对收件人加密，密封后 ≤ 4096 字节，明文上限 `MAX_INLINE_PLAINTEXT`）。
- 收：SSE 实时推送 + 定时拉收件箱；处理完标已读（通信节点随即删除）。
- 投递状态：查发件箱，`delivered` / `failed`（原因如 `sender-not-allowed`）。
- 陌生人规则：对方没把我加进允许名单时，只有第一条能到。加好友握手（§6）后双方互相加入允许名单。

## 3. 载荷分帧

一条逻辑消息 = `inner` 字节串，经 brotli 压缩（`inner` > 256 KiB 用 quality 5，否则 11）得到 `packed`，再按大小选一种帧：

| 首字节 | 名称 | 内容 |
|---|---|---|
| `0x21` | 单条 | `0x21 ‖ packed`（总长 ≤ 明文上限） |
| `0x22` | 分片 | `0x22 ‖ msg_id(16) ‖ index(u16 BE) ‖ total(u16 BE) ‖ chunk`，头 21 字节，最多 128 片 |
| `0x23` | 保留 | 不再使用；收到就丢弃。大附件走 §3.1 |

接收方按 `msg_id` 收齐全部分片后拼接、解压。分片 24 小时收不齐即丢弃。

`inner` = `u32 BE json_len ‖ JSON 信封（UTF-8）‖ blob`。没有附件时 `blob` 为空。JSON 信封最大 128 KiB，超过的收方丢弃，发方在入队时就拒绝（`too-large`）。

### 3.1 大附件

附件超过 170 KiB 时，文件本身不进消息：

1. 发送方把原始文件做成 TapeRelay 加密文件（`plan_encrypted_file`，`from` = 后台电路账号），收件人 = 自己 + 全部收件人（群 = 全体成员）。每个信封最多 16 个公钥（自己 + 15 人），人多就为同一个文件多做几个信封。
2. 对象（index、chunks、信封）上传到电路的通信节点：`GET <后台>/api/relay/sign` 拿 `{account, servers}`；`POST <后台>/api/relay/sign`（请求头用发送方身份签名，`server = "tapetape-backend"`）换取每个对象的上传签名；`POST https://<server>/v1/objects`。至少 2 台通信节点存好才算成功。
3. 消息照常是 `media` 信封，`blob` 为空，多一个字段 `ref` = 引用的 base64url（无填充）：

   ```
   reference = 0x01 ‖ n(u8) ‖ n × 信封 sha256(32) ‖ m(u8) ‖ m × (len(u8) ‖ "host:port")
   ```

   `size` 是文件的真实字节数；`w`/`h`/`dur`/`fname`/`text` 和普通附件一样，所以收方马上能显示占位卡片。
4. 收方按 `ref` 从任一通信节点取信封（`GET /v1/objects/<sha256 hex>`），找到能解开的那个，再取 index 和 chunks，逐个核对哈希后解密。是否自动下载由收方的「自动下载」设置决定。

通信节点名只允许小写字母、数字、`.`、`-` 加端口；引用最多 8 台通信节点。文件上限 100 MiB。

## 4. 信封

```jsonc
{
  "v": 1,
  "id": "q1w2e3…",            // 16 字节随机数，base64url 无填充；全局唯一，用于去重、回复、反应
  "ts": 1763600000000,         // 发送方毫秒时间
  "from": "key:0x…@relay2.taperelay.com:4246",   // 必须等于 TapeRelay 验证过的发件人，否则丢弃
  "name": "小王",              // 发送方当前昵称（顺带同步资料）
  "group": "g9f8…",            // 群消息带群 ID（16 字节 base64url）；单聊没有
  "type": "text",
  "expires": 3600,             // 可选：发送方这个会话当前的定时销毁秒数；没有或 0 = 关。收方发现和本地不同就更新会话设置
  "fwd": true,                 // 可选：转发的消息
  // 其余字段按 type
}
```

未知 `type` 和未知字段忽略（向前兼容）。

### 4.1 类型

| type | 字段 | 说明 |
|---|---|---|
| `text` | `text`, `reply?` | `reply` = 被回复消息的 `id` |
| `media` | `kind`(`image`/`video`/`voice`/`audio`/`file`/`gif`/`sticker`), `mime`, `fname?`(文件名；`name` 已被信封的昵称占用), `size`, `w?`, `h?`, `dur?`(毫秒), `text?`, `reply?`, `ref?` | 文件内容在 `blob`；大附件 `blob` 为空、文件在 `ref`（§3.1） |
| `reaction` | `target`, `emoji` | `emoji` 为空串表示撤销 |
| `edit` | `target`, `text` | 只能改自己发的文字 |
| `delete` | `targets[]` | 为所有人删除自己发的消息 |
| `read` | `targets[]` | 已读回执（设置里可关） |
| `profile` | `avatar?`, `bio?` | `avatar` = 头像的 MIME，头像在 `blob`；没有 `avatar` 表示没头像。昵称在信封 `name`。改资料、加好友完成时发给联系人 |
| `contact` | `account`, `cname` | 名片：分享一个联系人（`cname` 是名片上的名字） |
| `timer` | — | 只用来同步定时销毁设置（值在信封 `expires`），不显示成气泡 |
| `hello` | `token` | 加好友 / 入群握手请求（§6）；入群时信封带 `group` |
| `hello_ok` | — | 握手确认；入群时信封带 `group`，随后发 `group_state` |
| `group_state` | `gname`, `members[]`, `admins[]`, `names[]?`, `ver`, `avatar?` | 群的完整状态。`gname` 是群名（`name` 已被信封的昵称占用）；`members` / `admins` 是身份；`names` 和 `members` 一一对应，是发送方知道的昵称（收方只在自己没有这个人的昵称时采用）；`ver` 为毫秒时间，较新的覆盖较旧的；`avatar` = 群头像 MIME，图片在 `blob`，没有 = 无头像 |
| `redpacket` | 见 §5 | 发红包 |
| `redpacket_claim` | 见 §5 | 领红包通知（不在聊天里显示成气泡，只更新红包状态） |
| `transfer` | 见 §5 | 转账 |
| `transfer_claim` | 见 §5 | 收款通知 |
| `shop_item` | `url`, `title`, `price`, `image?` | 分享商品卡片（链上小店） |

### 4.2 群

- 群没有服务器。发群消息 = 给每个成员各发一份（信封相同，`group` 相同）。
- **群成员上限 500 人（含自己）**：建群、拉人超过时报 `group-full`；邀请链接入群时群已满就忽略；收到成员超过 500 的 `group_state` 直接丢弃。500 人、中文昵称的 `group_state` 约 86 KB，加上最大的群头像约 66 片。群里的 `reaction`、`timer`、`read` 也带 `group`；`read` 只发给被读消息的发送者。
- 创建、改名、改头像、加人、踢人：管理员发 `group_state` 给全体（含被移除的人，让他知道自己被移除）。目前所有成员都是管理员（旧版行为）。
- 退群：发一份不含自己的 `group_state` 给其余成员；收方发现被移除的正是发送者，就显示「X 退出了群」。
- 收到 `group_state`：`ver` 比本地新才接受；发送者必须是本地记录的管理员（新群除外：第一次收到、且成员里有自己时信任发送者）。
- 收到 `group_state` 后，把所有成员加入自己的 TapeRelay 允许名单，这样群里互不是好友的人也能互发。例外：拉你进新群的人不在你的允许名单里（陌生人拉群），这个群先算「请求」，接受后才加允许名单。
- 带 `group` 的 `text` / `media` / `contact` / `reaction` / `timer`：发送者必须是本地记录的当前成员，自己也必须还在群里，否则丢弃。

## 5. 钱包消息

链上动作由发送方自己签名并广播，确认上链后才发消息；消息只负责通知和展示，状态一律以链上为准。

```jsonc
// redpacket（只在群里）
{ "chain": "bsc", "contract": "0x…", "packet": "<红包 ID，UUID>", "token": "0x…|native", "symbol": "USDT",
  "total": "1.5", "count": 5, "greeting": "恭喜发财", "tx": "0x<创建交易>", "creator": "0x<发送者钱包>", "claim_key": "0x<32 字节领取私钥>" }
// redpacket_claim（领取后发给群里所有人）
{ "packet": "<红包 ID>", "claimer": "0x<领取者钱包>", "amount": "0.3", "tx": "0x<领取交易>" }
// transfer（只在 1:1）
{ "chain": "bsc", "contract": "0x…", "transfer": "<转账 ID，UUID>", "token": "0x…|native", "symbol": "USDT",
  "amount": "10", "note": "", "tx": "0x…", "creator": "0x…", "claim_key": "0x…" }
// transfer_claim
{ "transfer": "<转账 ID>", "tx": "0x…" }
```

合约是 `TapeTapeRedPacket`（源码、设计、已知局限见 `contracts/README.md`），不可升级。地址在 core 的 `chain.rs` 配置表里，没填的链不支持红包和转账。

- `chain`：`"bsc"`（56）或 `"xlayer"`（196）。`contract`、`token` 必须在接收方 core 的配置表里（同一条链的红包合约、已知代币），否则丢弃。
- 金额是十进制字符串，按代币精度精确（不能超过精度位数）。`total` / `amount` 是发送者付出的总额（含手续费）。`symbol` 只作参考，显示用配置表里的。
- `creator` 必须是信封发送者身份里的地址。红包在链上按创建者隔开：所有读写都带 `(creator, id)`。
- **领取密钥**：发送者每个红包随机生成一把 secp256k1 私钥，地址在创建时存进合约，私钥只放在这条端到端加密消息里。`claim_key` 不是合法私钥的消息直接丢弃。
- 创建：`createRedPacket(id, token, amount, count, claimKeyAddress, recipient, maxFeeRate)`。原生币 `token = 0`，`value = amount`；ERC-20 先 `approve`。红包 `recipient = 0`；转账 `count = 1`，`recipient` = 对方身份里的地址。`maxFeeRate` 填报价时读到的 `feeRate()`。合约按 `feeRate/1000` 从总额里扣手续费。
- 领取：`claimRedPacket(creator, id, signature)`。`signature` = 领取密钥对下面 192 字节做 `personal_sign`（先 keccak256，再加 `"\x19Ethereum Signed Message:\n32"` 前缀签名，r‖s‖v，v = 27/28）：
  `CLAIM_TAG ‖ chainId ‖ 合约地址 ‖ 创建者 ‖ keccak256(utf8(id)) ‖ 领取人`，每项 32 字节（地址左补零），`CLAIM_TAG = keccak256("TapeTapeRedPacket.claim.v1")`。签名绑定领取人，换地址无效。每个地址只能领一次；转账只有 `recipient` 能领。
- 7 天后（`block.timestamp ≥ createTime + 604800`）发送者可以 `refundRedPacket(id, to)` 退回剩余（客户端填自己），退回后不能再领。客户端在过期后不再显示「领取」（合约在退回前仍允许领取）。
- 接收方显示卡片前用 `getRedPacketInfo(creator, id)` 核对：创建者、代币、个数、`totalAmount + fee` = 卡片总额、`claimKey` = 消息里私钥的地址；转账还要 `recipient` = 自己（发送者那边 = 对方），红包 `recipient` 必须为 0。任何一项不符就显示为无效。`creator == 0` 表示链上没有这个红包。
- 红包的 `redpacket_claim` 在群里显示「X 领取了 Y 的红包」；`transfer_claim` 不显示，只刷新卡片。

## 6. 加好友与入群

邀请链接：`https://i.tapetape.org/#a=<身份 URL 编码>&n=<昵称>&t=<token>`，入群加 `&g=<群 ID>&gn=<群名>`。二维码内容同链接。

1. A 生成随机 `token`（16 字节，可重置，重置后旧链接失效）。
2. B 打开链接：把 A 加入允许名单，发 `hello{token}`，入群时信封带 `group`（陌生人第一条，能到）。
3. A 核对 `token`（入群用这个群自己的 token，同样可重置）：对就把 B 加入允许名单，回 `hello_ok`；入群时再把 B 加进群，发新的 `group_state` 给全体。B 在收到 `group_state` 之前只能看到一个「加入中」的空群。
4. B 收到 `hello_ok`，聊天变为「已验证」。

## 7. 资料与已读

- 昵称随每条消息的 `name` 同步；头像变更时发 `profile`。
- 消息状态：发出中 → 已发出（通信节点收下）→ 已送达（发件箱 `delivered`）→ 已读（收到 `read`）→ 失败（发件箱 `failed`，显示原因）。

## 8. 安全

- 接收时 `from` 必须等于 TapeRelay 验证的发件人；群消息的发送者必须是当前成员。
- `blob` 大小、分片数、JSON 长度都有上限（JSON ≤ 128 KiB，内联 `blob` ≤ 170 KiB，解压后的内层 ≤ 4 + 128 KiB + 170 KiB，分片 ≤ 128，大附件 ≤ 100 MiB）。超限的消息整条丢弃。
- 大附件只从接收方自己配置的后台给出的通信节点列表里下载，引用里的其他通信节点不连。
- `redpacket_claim` 要在链上 `getClaimRecords` 里查到发送者地址才显示「X 领取了红包」。
- 链接、文件名只当数据显示，不执行。
