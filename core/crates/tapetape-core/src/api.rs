#[derive(Debug, thiserror::Error, uniffi::Error)]
#[uniffi(flat_error)]
pub enum CoreError {
    #[error("{message}")]
    Failed { message: String },
}

impl From<anyhow::Error> for CoreError {
    fn from(e: anyhow::Error) -> Self {
        log::debug!("{e:#}");
        CoreError::Failed {
            message: e.to_string(),
        }
    }
}

impl From<rusqlite::Error> for CoreError {
    fn from(e: rusqlite::Error) -> Self {
        anyhow::Error::from(e).into()
    }
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct AccountInfo {
    pub id: u32,
    pub address: String,
    pub account: String,
    pub name: String,
    pub avatar_path: Option<String>,
    pub node: String,
    pub bio: String,
    pub muted: bool,
    pub tag: String,
    pub unread: u32,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Contact {
    pub id: u32,
    pub account: String,
    pub address: String,
    pub name: String,
    pub avatar_path: Option<String>,
    pub verified: bool,
    pub bio: String,
    pub last_seen: Option<i64>,
    pub blocked: bool,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct ChatSummary {
    pub id: u32,
    pub name: String,
    pub avatar_path: Option<String>,
    pub is_group: bool,
    pub last_text: Option<String>,
    pub last_time: i64,
    pub last_state: Option<MessageState>,
    pub unread: u32,
    pub pinned: bool,
    pub muted: bool,
    pub draft: Option<String>,
    pub archived: bool,
    pub is_self: bool,
    pub is_device: bool,
    pub is_request: bool,
    pub last_kind: Option<MessageKind>,
    pub last_from_me: bool,
    pub last_seen: Option<i64>,
    pub last_sender: Option<String>,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Chat {
    pub id: u32,
    pub name: String,
    pub avatar_path: Option<String>,
    pub is_group: bool,
    pub contact_id: Option<u32>,
    pub verified: bool,
    pub is_self: bool,
    pub is_device: bool,
    pub is_request: bool,
    pub can_send: bool,
    pub archived: bool,
    pub pinned: bool,
    pub muted: bool,
    pub muted_until: Option<i64>,
    pub ephemeral_seconds: u32,
    pub blocked: bool,
    pub member_count: u32,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Reaction {
    pub emoji: String,
    pub count: u32,
    pub mine: bool,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct ReactionBy {
    pub contact_id: Option<u32>,
    pub name: String,
    pub emoji: String,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Vcard {
    pub name: String,
    pub account: String,
    pub address: String,
}

#[derive(Debug, Clone, PartialEq, serde::Serialize, serde::Deserialize, uniffi::Enum)]
pub enum SystemMessage {
    TimerChanged {
        seconds: u32,
    },
    GroupCreated {
        name: String,
    },
    GroupNameChanged {
        name: String,
        #[serde(default)]
        old_name: String,
    },
    GroupAvatarChanged,
    MemberAdded {
        contact_id: Option<u32>,
        name: String,
    },
    MemberRemoved {
        contact_id: Option<u32>,
        name: String,
    },
    MemberLeft,
    PacketClaimed {
        claimer: Option<u32>,
        owner: Option<u32>,
        packet_msg_id: Option<u32>,
    },
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct GroupMember {
    pub contact_id: Option<u32>,
    pub name: String,
    pub account: String,
    pub address: String,
    pub avatar_path: Option<String>,
    pub admin: bool,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct GroupInfo {
    pub chat_id: u32,
    pub name: String,
    pub avatar_path: Option<String>,
    pub members: Vec<GroupMember>,
    pub is_member: bool,
    pub is_admin: bool,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Delivery {
    pub contact_id: u32,
    pub name: String,
    pub state: MessageState,
    pub error: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum MediaQuality {
    Balanced,
    Worse,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum AutoDownload {
    All,
    Kib160,
    Kib640,
    Mib5,
    Mib25,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Settings {
    pub read_receipts: bool,
    pub media_quality: MediaQuality,
    pub auto_download: AutoDownload,
    pub delete_device_after: Option<u32>,
    pub mentions: bool,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Notification {
    pub account_id: u32,
    pub chat_id: u32,
    pub msg_id: u32,
    pub chat_name: String,
    pub sender_name: String,
    pub is_group: bool,
    pub kind: MessageKind,
    pub text: String,
    pub reaction: Option<String>,
    pub muted: bool,
    pub mention: bool,
}

#[derive(Debug, Clone, PartialEq, Default, uniffi::Record)]
pub struct FetchResult {
    pub new_messages: u32,
    pub notifications: Vec<Notification>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum BackupProtection {
    Passphrase,
    RecoveryPhrase,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Message {
    pub id: u32,
    pub chat_id: u32,
    pub from_contact: Option<u32>,
    pub sender_name: String,
    pub text: String,
    pub time: i64,
    pub state: MessageState,
    pub error: Option<String>,
    pub reply_to: Option<u32>,
    pub kind: MessageKind,
    pub file_path: Option<String>,
    pub file_name: Option<String>,
    pub mime: Option<String>,
    pub size: Option<u64>,
    pub width: Option<u32>,
    pub height: Option<u32>,
    pub duration_ms: Option<u32>,
    pub vcard: Option<Vcard>,
    pub reactions: Vec<Reaction>,
    pub forwarded: bool,
    pub saved: bool,
    pub original_id: Option<u32>,
    pub expires_at: Option<i64>,
    pub system: Option<SystemMessage>,
    pub packet: Option<Packet>,
    pub download: Option<DownloadState>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum DownloadState {
    Available,
    Downloading,
    Failed,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum MessageKind {
    Text,
    System,
    Image,
    Gif,
    Sticker,
    Video,
    Voice,
    Audio,
    File,
    Vcard,
    RedPacket,
    Transfer,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum MediaKind {
    Image,
    Gif,
    Sticker,
    Video,
    Voice,
    Audio,
    File,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum MessageState {
    Pending,
    Sent,
    Delivered,
    Read,
    Failed,
    Incoming,
    Seen,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum Connectivity {
    NotConnected,
    Connecting,
    Connected,
}

#[derive(Debug, Clone, PartialEq, uniffi::Enum)]
pub enum Event {
    ChatListChanged,
    MessagesChanged { chat_id: u32 },
    IncomingMessage { chat_id: u32, msg_id: u32 },
    MessageStateChanged { chat_id: u32, msg_id: u32 },
    IncomingReaction { chat_id: u32, msg_id: u32 },
    ContactsChanged,
    ConnectivityChanged,
    Error { message: String },
    Notification { notification: Notification },
    TransferProgress { msg_id: u32, done: u64, total: u64 },
}

#[uniffi::export(callback_interface)]
pub trait EventListener: Send + Sync {
    fn on_event(&self, account_id: u32, event: Event);
}

const KINDS: [MessageKind; 12] = [
    MessageKind::Text,
    MessageKind::System,
    MessageKind::Image,
    MessageKind::Gif,
    MessageKind::Sticker,
    MessageKind::Video,
    MessageKind::Voice,
    MessageKind::Audio,
    MessageKind::File,
    MessageKind::Vcard,
    MessageKind::RedPacket,
    MessageKind::Transfer,
];

impl MessageKind {
    pub(crate) fn code(self) -> i64 {
        KINDS.iter().position(|k| *k == self).unwrap() as i64
    }

    pub(crate) fn from_code(c: i64) -> MessageKind {
        KINDS.get(c as usize).copied().unwrap_or(MessageKind::Text)
    }

    pub(crate) fn wire(self) -> &'static str {
        match self {
            MessageKind::Image => "image",
            MessageKind::Gif => "gif",
            MessageKind::Sticker => "sticker",
            MessageKind::Video => "video",
            MessageKind::Voice => "voice",
            MessageKind::Audio => "audio",
            _ => "file",
        }
    }

    pub(crate) fn from_wire(s: &str) -> MessageKind {
        match s {
            "image" => MessageKind::Image,
            "gif" => MessageKind::Gif,
            "sticker" => MessageKind::Sticker,
            "video" => MessageKind::Video,
            "voice" => MessageKind::Voice,
            "audio" => MessageKind::Audio,
            _ => MessageKind::File,
        }
    }
}

impl From<PacketKind> for MessageKind {
    fn from(k: PacketKind) -> Self {
        match k {
            PacketKind::RedPacket => MessageKind::RedPacket,
            PacketKind::Transfer => MessageKind::Transfer,
        }
    }
}

impl From<MediaKind> for MessageKind {
    fn from(k: MediaKind) -> Self {
        match k {
            MediaKind::Image => MessageKind::Image,
            MediaKind::Gif => MessageKind::Gif,
            MediaKind::Sticker => MessageKind::Sticker,
            MediaKind::Video => MessageKind::Video,
            MediaKind::Voice => MessageKind::Voice,
            MediaKind::Audio => MessageKind::Audio,
            MediaKind::File => MessageKind::File,
        }
    }
}

impl MessageState {
    pub(crate) fn code(self) -> i64 {
        match self {
            MessageState::Pending => 0,
            MessageState::Sent => 1,
            MessageState::Delivered => 2,
            MessageState::Read => 3,
            MessageState::Failed => 4,
            MessageState::Incoming => 5,
            MessageState::Seen => 6,
        }
    }

    pub(crate) fn from_code(c: i64) -> MessageState {
        match c {
            0 => MessageState::Pending,
            1 => MessageState::Sent,
            2 => MessageState::Delivered,
            3 => MessageState::Read,
            4 => MessageState::Failed,
            5 => MessageState::Incoming,
            _ => MessageState::Seen,
        }
    }
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Token {
    pub id: String,
    pub chain_id: u64,
    pub chain_name: String,
    pub symbol: String,
    pub address: Option<String>,
    pub decimals: u8,
    pub packets: bool,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Balance {
    pub token_id: String,
    pub amount: Option<String>,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct SendEstimate {
    pub amount: String,
    pub fee: String,
    pub fee_symbol: String,
    pub balance: String,
    pub max: String,
    pub error: Option<String>,
}

#[derive(Debug, Clone, PartialEq, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct WalletTx {
    pub chain_id: u64,
    pub token_id: String,
    pub to: String,
    pub amount: String,
    pub tx_hash: String,
    pub created_at: i64,
    /// 广场网页发起的交易（合约调用，amount 只是附带的原生币）：钱包里显示成「网页交易」，不算提现
    #[serde(default)]
    pub dapp: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum TxStatus {
    Pending,
    Confirmed,
    Failed,
    NotFound,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize, serde::Deserialize, uniffi::Enum)]
pub enum PacketKind {
    RedPacket,
    Transfer,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize, serde::Deserialize, uniffi::Enum)]
pub enum PacketState {
    Creating,
    Open,
    Finished,
    Expired,
    Refunded,
    Failed,
}

#[derive(Debug, Clone, PartialEq, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct ClaimRecord {
    pub address: String,
    pub amount: String,
    pub contact_id: Option<u32>,
    pub me: bool,
}

#[derive(Debug, Clone, PartialEq, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct PacketStatus {
    pub state: PacketState,
    pub claimed_count: u32,
    pub total_count: u32,
    pub claimed_amount: String,
    pub my_amount: Option<String>,
    pub can_claim: bool,
    pub can_refund: bool,
    pub expires_at: Option<i64>,
    pub records: Vec<ClaimRecord>,
    pub checked_at: i64,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Packet {
    pub kind: PacketKind,
    pub packet_id: String,
    pub chain_id: u64,
    pub token_id: Option<String>,
    pub symbol: String,
    pub amount: String,
    pub count: u32,
    pub text: String,
    pub tx: String,
    pub creator: String,
    pub mine: bool,
    pub status: PacketStatus,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct ShopInfo {
    pub address: String,
    pub balance: String,
    pub open_cost: String,
    pub ready: bool,
    pub name: Option<String>,
    pub app_url: Option<String>,
    pub share_url: Option<String>,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct ProductInput {
    pub description: String,
    pub original_price: u64,
    pub discount_price: u64,
    pub images: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, serde::Serialize, serde::Deserialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct Product {
    pub id: String,
    pub description: String,
    pub original_price: u64,
    pub discount_price: u64,
    pub images: Vec<String>,
    pub created_at: i64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum ProxyKind {
    Http,
    Https,
    Socks5,
    Shadowsocks,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct ProxyInfo {
    pub url: String,
    pub kind: ProxyKind,
    pub host: String,
    pub port: u16,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct ProxySettings {
    pub enabled: bool,
    pub active: Option<String>,
    pub proxies: Vec<ProxyInfo>,
}
