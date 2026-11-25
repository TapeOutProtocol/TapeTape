//! 广场浏览器里的网页钱包（EIP-1193 的原生一侧）。
//!
//! 网页通过注入的 `window.ethereum` 发请求，App 原生层负责「这个网站能不能连」「这笔要不要签」的确认弹窗，
//! 这里做的事：只读的链上查询（限定方法、按网站限频）、生成交易/签名的确认信息（看得懂的那种）、用户确认后签名。
//!
//! 安全上的硬规定（2026-11-24 两轮五角色审计，见 browser/钱包连接安全审计-20261124.md、钱包连接安全复审-20261124.md）：
//! - 要签的摘要一律在这里算（交易、personal_sign、EIP-712），不接受网页算好的哈希；
//! - 确认弹窗上显示的就是实际签的东西：EIP-712 只显示按类型真正签进去的字段（不截断），有多余字段直接拒绝，
//!   EIP712Domain 只认标准的 5 个字段和标准类型，必须带 chainId 且等于当前链；
//! - 网页或合约给的文字（字符串字段、应用名、合约说明、代币名、消息）：看不见的字符换成可见标记，
//!   单行的地方把换行显示成 ⏎，字符串字段按 JSON 转义加引号——伪造不出「App 自己写的行」；
//! - 交易识别常见的代币/NFT 授权与转账；参数里多塞数据、布尔值不规范的一律当危险；
//!   识别不出来的合约调用也当危险（标红 + 再确认）。EIP-712 只要有收款/授权地址、数额、调用数据或可疑类型名就当危险；
//! - 只支持 X Layer 和 BNB 链；不支持部署合约、eth_sign、eth_signTransaction；
//! - 网页给的 gas 上限最多取估算值的 2 倍；发交易前重新估算，手续费涨一半以上就停；
//!   确认和发送用一张内核签发的票据绑在一起（链号 + 交易内容 + 手续费）；
//! - 只读请求和交易估算按网站限频（每秒 5 次，最多攒 20 次），eth_getLogs 必须带合约地址、区块范围不超过 2000。

use std::collections::{BTreeMap, BTreeSet, HashMap};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::{Duration, Instant};

use anyhow::{bail, ensure, Context, Result};
use log::debug;
use serde_json::{json, Value};
use taperelay::evm::{self, Abi};
use taperelay::Identity;

use crate::account::Account;
use crate::api::{CoreError, WalletTx};
use crate::chain::{self, format_amount, hex0x, Call, Rpc};
use crate::protocol::now_ms;

/// 网页能直接问节点的只读方法。签名、发交易、账户相关的不在这里（走确认流程）
const READ_METHODS: &[&str] = &[
    "eth_blockNumber",
    "eth_call",
    "eth_estimateGas",
    "eth_gasPrice",
    "eth_getBalance",
    "eth_getCode",
    "eth_getTransactionCount",
    "eth_getTransactionReceipt",
    "eth_getTransactionByHash",
    "eth_getBlockByNumber",
    "eth_getLogs",
    "eth_getStorageAt",
    "eth_maxPriorityFeePerGas",
    "eth_feeHistory",
];

/// 只读请求的限频：每个网站每秒 5 次，最多攒 20 次（网页刷不爆 tapetape.org 中转上这个用户的额度）
const RATE_PER_SEC: f64 = 5.0;
const RATE_BURST: f64 = 20.0;
/// eth_getLogs 一次最多查这么多个区块
const LOGS_MAX_BLOCKS: u64 = 2000;
/// EIP-712 的大小限制（太深、太多的类型会让解析耗尽栈或时间）
const MAX_TYPED_JSON: usize = 64 * 1024;
const MAX_TYPES: usize = 64;
const MAX_FIELDS: usize = 64;
/// 消息签名最多显示这么多行，再多就当看不懂的数据
const MAX_MESSAGE_LINES: usize = 60;
/// 比这个大的 JSON 数字在浏览器里已经不精确了，必须写成字符串
const MAX_SAFE_JSON_INT: u64 = (1 << 53) - 1;

/// 交易的确认信息（原生弹窗显示它，用户点确认后带着 fee 和 ticket 回来发）
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct DappTx {
    pub chain_id: u64,
    pub chain_name: String,
    /// 交易直接发给的地址（合约调用时是合约地址）
    pub to: String,
    pub to_is_contract: bool,
    /// 转出的原生币数量（精确，已格式化），没有就是 "0"
    pub value: String,
    pub symbol: String,
    /// 调用数据的长度（字节）；0 = 普通转账
    pub data_len: u32,
    /// 调用的函数选择器（0x + 8 位），普通转账为空
    pub selector: Option<String>,
    /// 识别出的操作：approve / revoke / increase-allowance / transfer / transfer-from / approval-for-all /
    /// revoke-all / permit2-approve / nft-approve / nft-transfer-from；识别不出来是 None
    pub action: Option<String>,
    /// 被授权方 / 收款方 / operator
    pub counterparty: Option<String>,
    /// transferFrom 的转出方
    pub from: Option<String>,
    /// 涉及的代币/NFT 合约
    pub token: Option<String>,
    /// 代币数量（按代币自己的小数位格式化）或 NFT 编号；无限额度时为 None、unlimited 为 true
    pub token_amount: Option<String>,
    /// 代币名字：认识的代币取自 App 的代币表（token_known），否则是合约自己说的
    pub token_symbol: Option<String>,
    pub token_known: bool,
    pub unlimited: bool,
    /// Permit2 授权的到期时间（UTC）
    pub expiry: Option<String>,
    /// 是 NFT（token_amount 是编号）
    pub nft: bool,
    /// 读不到小数位：token_amount 是最小单位的整数
    pub raw_amount: bool,
    /// 调用数据比函数需要的多（合约会忽略多出来的部分，但这是可疑写法）
    pub extra_data: bool,
    /// 要标红、再确认一次：授权类操作、识别不出来的合约调用、可疑写法
    pub danger: bool,
    pub gas: String,
    /// 预计网络手续费（精确，已格式化，单位 symbol）
    pub fee: String,
    pub balance: String,
    /// 发送时原样交回 dapp_send_tx，绑定这次确认的链号、交易内容和手续费
    pub ticket: String,
    /// 不能发的原因：insufficient-balance / insufficient-gas / will-fail: 合约说明 / no-contract-creation / chain-mismatch …
    pub error: Option<String>,
}

/// EIP-712 签名请求的摘要（原生弹窗显示）
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct DappTypedData {
    /// 网站在签名里自称的应用名（单行、可见字符；弹窗上要标「网站自称」）
    pub domain_name: String,
    pub verifying_contract: String,
    pub chain_id: String,
    pub primary_type: String,
    /// 按类型定义、按定义顺序列出的实际签名字段（不截断；字符串按 JSON 转义；domain 的其他字段在最后）
    pub fields: String,
    /// 可能让对方直接动用资产的签名（Permit、授权转账、代执行、挂单……）
    pub danger: bool,
    /// 签名里的被授权方 / 收款方（全部列出、不含本钱包；挂单类为 None = 任何成交方）
    pub counterparty: Option<String>,
}

/// personal_sign 的预览
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct DappMessage {
    /// 实际要签的字节（0x 十六进制），签名时原样交回 dapp_personal_sign
    pub hex: String,
    /// 给人看的内容：能读的文字（看不见的字符已换成可见标记、空行压缩、最多 60 行），读不了就是十六进制
    pub text: String,
    /// 看不懂的数据（很可能是哈希）：签了可能等于授权
    pub blind: bool,
}

fn sign_digest(id: &Identity, digest: &[u8; 32]) -> String {
    let (sig, recid) = id.sign_recoverable(digest);
    hex0x(&[&sig[..], &[27 + recid]].concat())
}

/// personal_sign（EIP-191）：keccak256("\x19Ethereum Signed Message:\n" + 字节数 + 消息)
pub(crate) fn personal_digest(message: &[u8]) -> [u8; 32] {
    let prefix = format!("\x19Ethereum Signed Message:\n{}", message.len());
    evm::keccak256(&[prefix.as_bytes(), message].concat())
}

/// 严格的 0x 十六进制：必须 0x 开头、偶数长度、只有 0-9a-fA-F
fn parse_hex(s: &str) -> Result<Vec<u8>> {
    let h = s.strip_prefix("0x").context("hex must start with 0x")?;
    ensure!(h.len() % 2 == 0 && h.bytes().all(|b| b.is_ascii_hexdigit()), "bad hex");
    Ok(hex::decode(h)?)
}

/// 严格的地址：0x + 40 位十六进制；大小写混写的必须符合 EIP-55 校验
fn parse_address_strict(s: &str) -> Result<[u8; 20]> {
    let h = s.strip_prefix("0x").context("address must start with 0x")?;
    ensure!(h.len() == 40 && h.bytes().all(|b| b.is_ascii_hexdigit()), "bad address");
    let has_upper = h.bytes().any(|b| b.is_ascii_uppercase());
    let has_lower = h.bytes().any(|b| b.is_ascii_lowercase());
    if has_upper && has_lower {
        let lower = h.to_ascii_lowercase();
        let hash = evm::keccak256(lower.as_bytes());
        for (i, c) in h.bytes().enumerate() {
            if c.is_ascii_alphabetic() {
                let nibble = (hash[i / 2] >> if i % 2 == 0 { 4 } else { 0 }) & 0xf;
                ensure!((nibble >= 8) == c.is_ascii_uppercase(), "bad address checksum");
            }
        }
    }
    let mut a = [0u8; 20];
    a.copy_from_slice(&hex::decode(h)?);
    Ok(a)
}

// ---------------- 给人看的文字：标出看不见的字符 ----------------

/// 看不见、会骗人眼的字符：控制符、格式符（双向文字控制、零宽）、行/段分隔符、默认可忽略字符、
/// 看起来是空白的填充符、不换行空白（会让一行硬折成很多「空白行」）、私用区字符（苹果设备上 U+F8FF 显示成苹果标志）
fn is_hidden(c: char) -> bool {
    let u = c as u32;
    (u < 0x20 && c != '\n' && c != '\t')
        || (0x7f..=0x9f).contains(&u)
        || matches!(
            u,
            0x00a0 | 0x00ad | 0x034f | 0x061c | 0x115f | 0x1160 | 0x17b4 | 0x17b5 | 0x180b..=0x180f
                | 0x2007 | 0x200b..=0x200f | 0x2028..=0x202f | 0x2060..=0x206f | 0x2800 | 0x3164
                | 0xe000..=0xf8ff | 0xfe00..=0xfe0f | 0xfeff | 0xffa0 | 0xfff0..=0xfffb | 0x1bca0..=0x1bca3
                | 0x1d173..=0x1d17a | 0xe0000..=0xe0fff | 0xf0000..=0x10ffff
        )
}

/// emoji 一类的符号：后面跟的变体选择符（U+FE0F）、零宽连接符（U+200D）属于 emoji 本身，不标出来
fn is_emoji_base(c: char) -> bool {
    matches!(c as u32, 0x2300..=0x27bf | 0x2b00..=0x2bff | 0x1f000..=0x1faff)
}

/// 组合符号（叠加在前一个字上的那种）：连续很多个会在视觉上盖住上下行
fn is_combining(c: char) -> bool {
    matches!(c as u32, 0x0300..=0x036f | 0x0483..=0x0489 | 0x1ab0..=0x1aff | 0x1dc0..=0x1dff | 0x20d0..=0x20ff | 0xfe20..=0xfe2f)
}

fn mark_hidden(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    let mut prev: Option<char> = None;
    let mut combining = 0;
    for c in s.chars() {
        let emoji_part = matches!(c as u32, 0xfe0f | 0x200d) && prev.is_some_and(is_emoji_base);
        combining = if is_combining(c) { combining + 1 } else { 0 };
        if (is_hidden(c) && !emoji_part) || combining > 2 {
            out.push_str(&format!("⟦U+{:04X}⟧", c as u32));
        } else {
            out.push(c);
        }
        // 零宽连接符后面接着的下一个 emoji 仍算 emoji 序列
        if !(c as u32 == 0x200d && emoji_part) && !is_combining(c) {
            prev = Some(c);
        }
    }
    out
}

/// 一行里连续 4 个以上的空白压成「␣×N」（几千个空格能把后面的内容推出屏幕）
fn squeeze(line: &str) -> String {
    let mut out = String::with_capacity(line.len());
    let mut run = 0usize;
    let flush = |out: &mut String, run: usize| {
        if run >= 4 {
            out.push_str(&format!(" ␣×{run} "));
        } else {
            out.extend(std::iter::repeat(' ').take(run));
        }
    };
    for c in line.chars() {
        if c.is_whitespace() {
            run += 1;
        } else {
            flush(&mut out, run);
            run = 0;
            out.push(c);
        }
    }
    flush(&mut out, run);
    out
}

/// 截到 max 个字符，超出的写「…(+N)」。返回（文字，是否截断）
fn clip(s: String, max: usize) -> (String, bool) {
    let n = s.chars().count();
    if n <= max {
        (s, false)
    } else {
        (format!("{}…(+{})", s.chars().take(max).collect::<String>(), n - max), true)
    }
}

/// 单行显示：换行显示成 ⏎，看不见的字符标出来，连续空白压缩，超长截断
pub(crate) fn oneline(s: &str, max: usize) -> String {
    let s = squeeze(&mark_hidden(&s.replace("\r\n", "\n").replace(['\n', '\r'], "⏎")));
    if s.chars().count() <= max {
        s
    } else {
        format!("{}…", s.chars().take(max).collect::<String>())
    }
}

/// 一行最多显示这么多字符
const MAX_LINE: usize = 300;

/// 多行显示：看不见的字符标出来；行内连续空白压缩；只含空白的行算空行（输出成真正的空行，连续的只留一行）；
/// 每行最多 MAX_LINE 个字符，最多 max_lines 行。返回（文字，有没有省略内容）
fn multiline(s: &str, max_lines: usize) -> (String, bool) {
    let mut lines: Vec<String> = Vec::new();
    let mut prev_blank = false;
    let mut clipped = false;
    for raw in s.replace("\r\n", "\n").replace('\r', "\n").split('\n') {
        let line = mark_hidden(raw);
        let blank = line.chars().all(char::is_whitespace);
        if blank {
            if !prev_blank {
                lines.push(String::new());
            }
            prev_blank = true;
            continue;
        }
        prev_blank = false;
        let (line, cut) = clip(squeeze(&line), MAX_LINE);
        clipped |= cut;
        lines.push(line);
    }
    while lines.len() > 1 && lines.last().is_some_and(String::is_empty) {
        lines.pop();
    }
    let omitted = lines.len().saturating_sub(max_lines);
    lines.truncate(max_lines);
    let mut text = lines.join("\n");
    if omitted > 0 {
        text.push_str(&format!("\n⋯ (+{omitted})"));
    }
    (text, clipped || omitted > 0)
}

/// 字符串字段：JSON 转义（换行显示成 \n、引号转义）后再标出看不见的字符、压缩连续空白——写不出假的「字段: 值」行
fn quoted(s: &str) -> String {
    clip(squeeze(&mark_hidden(&serde_json::to_string(s).unwrap_or_default())), 2000).0
}

// ---------------- 256 位整数 ----------------

/// 32 字节无符号大整数 → 十进制
fn word_to_decimal(w: &[u8; 32]) -> String {
    let mut n = *w;
    let mut digits = Vec::new();
    while n.iter().any(|b| *b != 0) {
        let mut rem = 0u32;
        for b in n.iter_mut() {
            let cur = (rem << 8) | u32::from(*b);
            *b = (cur / 10) as u8;
            rem = cur % 10;
        }
        digits.push(b'0' + rem as u8);
    }
    if digits.is_empty() {
        return "0".into();
    }
    digits.reverse();
    String::from_utf8(digits).unwrap()
}

/// 按小数位格式化（精确，不四舍五入），去掉末尾的 0
fn format_units(w: &[u8; 32], decimals: u8) -> String {
    let s = word_to_decimal(w);
    let d = decimals as usize;
    if d == 0 {
        return s;
    }
    let s = format!("{s:0>width$}", width = d + 1);
    let (int, frac) = s.split_at(s.len() - d);
    let frac = frac.trim_end_matches('0');
    if frac.is_empty() { int.to_string() } else { format!("{int}.{frac}") }
}

fn strip_hex_prefix(s: &str) -> Option<&str> {
    s.strip_prefix("0x").or_else(|| s.strip_prefix("0X"))
}

/// EIP-712 用：把 JSON 里的数（十进制字符串、0x 十六进制字符串、不超过 2^53 的 JSON 整数）编码成 32 字节；负数按二进制补码
fn word_of_number(v: &Value, signed: bool, bits: usize) -> Result<[u8; 32]> {
    let (neg, digits): (bool, String) = match v {
        Value::Number(n) => {
            // 1.0、-0 这种写法的整数照收（和 ethers 一致）；真正带小数的拒绝
            let s = match n.as_f64() {
                Some(f) if !n.is_i64() && !n.is_u64() && f.fract() == 0.0 && f.abs() <= MAX_SAFE_JSON_INT as f64 => format!("{}", f as i64),
                _ => n.to_string(),
            };
            ensure!(!s.contains(['.', 'e', 'E']), "not an integer: {s}");
            // 超过 2^53 的 JSON 数字在网页那边已经不精确了：签哪个值都可能和网页本意不同
            let mag = s.trim_start_matches('-');
            ensure!(mag.parse::<u64>().is_ok_and(|m| m <= MAX_SAFE_JSON_INT), "typed-unsafe-number");
            match s.strip_prefix('-') {
                Some(r) => (true, r.to_string()),
                None => (false, s),
            }
        }
        Value::String(s) => {
            let s = s.trim();
            match s.strip_prefix('-') {
                Some(r) => (true, r.to_string()),
                None => (false, s.to_string()),
            }
        }
        _ => bail!("not a number: {v}"),
    };
    let mut w = [0u8; 32];
    if let Some(h) = strip_hex_prefix(&digits) {
        ensure!(!h.is_empty() && h.bytes().all(|b| b.is_ascii_hexdigit()), "bad number: {digits}");
        let h = if h.len() % 2 == 1 { format!("0{h}") } else { h.to_string() };
        let b: Vec<u8> = hex::decode(h)?.into_iter().skip_while(|x| *x == 0).collect();
        ensure!(b.len() <= 32, "number too large");
        w[32 - b.len()..].copy_from_slice(&b);
    } else {
        ensure!(!digits.is_empty() && digits.bytes().all(|c| c.is_ascii_digit()), "bad number: {digits}");
        for c in digits.bytes() {
            let mut carry = u32::from(c - b'0');
            for byte in w.iter_mut().rev() {
                let x = u32::from(*byte) * 10 + carry;
                *byte = (x & 0xff) as u8;
                carry = x >> 8;
            }
            ensure!(carry == 0, "number too large");
        }
    }
    // "-0" 就是 0
    let neg = neg && w != [0u8; 32];
    ensure!(!neg || signed, "negative value for unsigned type");
    // 位宽检查：无符号不超过 bits 位；有符号的绝对值不超过 2^(bits-1)
    let limit_bits = if signed { bits - 1 } else { bits };
    let used = 256 - w.iter().position(|b| *b != 0).map_or(256, |i| i * 8 + w[i].leading_zeros() as usize);
    let at_min = signed && neg && used == limit_bits + 1 && {
        let mut m = [0u8; 32];
        m[31 - limit_bits / 8] = 1 << (limit_bits % 8);
        m == w
    };
    ensure!(used <= limit_bits || at_min, "number out of range for {}int{bits}", if signed { "" } else { "u" });
    if neg {
        for b in w.iter_mut() {
            *b = !*b;
        }
        for b in w.iter_mut().rev() {
            let (x, o) = b.overflowing_add(1);
            *b = x;
            if !o {
                break;
            }
        }
    }
    Ok(w)
}

/// 交易字段里的数：只接受 0x 十六进制字符串或非负 JSON 整数（JSON-RPC 规定的写法），十进制字符串一律拒绝
fn quantity_of(v: &Value) -> Result<u128> {
    let w = match v {
        Value::String(s) if strip_hex_prefix(s).is_some() => word_of_number(v, false, 256)?,
        Value::Number(_) => word_of_number(v, false, 256)?,
        _ => bail!("bad-number"),
    };
    ensure!(w[..16].iter().all(|b| *b == 0), "number too large");
    Ok(u128::from_be_bytes(w[16..].try_into().unwrap()))
}

/// 极大的额度（高 128 位不为 0）当作「无限」
fn is_unlimited(w: &[u8; 32]) -> bool {
    w[..16].iter().any(|b| *b != 0)
}

/// 秒级时间戳 → UTC 日期时间（只用来给人看）
fn utc_time(secs: u64) -> String {
    let days = (secs / 86400) as i64;
    let rem = secs % 86400;
    // Howard Hinnant 的 civil_from_days
    let z = days + 719468;
    let era = z.div_euclid(146097);
    let doe = z - era * 146097;
    let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    let y = yoe + era * 400 + i64::from(m <= 2);
    format!("{y:04}-{m:02}-{d:02} {:02}:{:02} UTC", rem / 3600, rem % 3600 / 60)
}

// ---------------- EIP-712（eth_signTypedData_v4 / v3）----------------

type Types = BTreeMap<String, Vec<(String, String)>>;

fn is_ident(s: &str) -> bool {
    let mut it = s.chars();
    matches!(it.next(), Some(c) if c.is_ascii_alphabetic() || c == '_') && it.all(|c| c.is_ascii_alphanumeric() || c == '_')
}

/// 基础类型：address bool string bytes bytes1..32 int/uint（8 的倍数，8..256；不写位数 = 256）
fn is_atomic(t: &str) -> bool {
    match t {
        "address" | "bool" | "string" | "bytes" | "int" | "uint" => true,
        _ => {
            if let Some(n) = t.strip_prefix("bytes") {
                return n.parse::<usize>().is_ok_and(|n| (1..=32).contains(&n)) && !n.starts_with('0');
            }
            let n = t.strip_prefix("uint").or_else(|| t.strip_prefix("int"));
            n.is_some_and(|n| !n.starts_with('0') && n.parse::<usize>().is_ok_and(|b| b % 8 == 0 && (8..=256).contains(&b)))
        }
    }
}

/// 拆数组后缀：Item[][3] → (Item, 2 层)；后缀写法不对返回 None
fn split_array(t: &str) -> Option<(&str, usize)> {
    let base = t.split('[').next()?;
    let mut rest = &t[base.len()..];
    let mut dims = 0;
    while !rest.is_empty() {
        let close = rest.find(']')?;
        let inner = &rest[1..close];
        if !rest.starts_with('[') || !(inner.is_empty() || (inner.bytes().all(|b| b.is_ascii_digit()) && !inner.starts_with('0'))) {
            return None;
        }
        dims += 1;
        rest = &rest[close + 1..];
    }
    Some((base, dims))
}

fn base_of(t: &str) -> &str {
    t.split('[').next().unwrap_or(t)
}

fn parse_types(v: &Value) -> Result<Types> {
    let obj = v.as_object().context("types must be an object")?;
    ensure!(obj.len() <= MAX_TYPES, "typed-too-large");
    let mut out = Types::new();
    for (name, fields) in obj {
        let mut seen = BTreeSet::new();
        let list = fields.as_array().with_context(|| format!("type {name} must be a list"))?;
        ensure!(list.len() <= MAX_FIELDS, "typed-too-large");
        let list = list
            .iter()
            .map(|f| {
                let n = f["name"].as_str().context("field without name")?.to_string();
                let t = f["type"].as_str().context("field without type")?.to_string();
                ensure!(is_ident(&n) && n.len() <= 64, "bad field name {n}");
                ensure!(seen.insert(n.clone()), "duplicate field {n} in {name}");
                Ok((n, t))
            })
            .collect::<Result<Vec<_>>>()?;
        out.insert(name.clone(), list);
    }
    // 结构体名必须是标识符、不能冒充基础类型；每个字段的类型必须是基础类型或已定义的结构体
    for (name, fields) in &out {
        ensure!(is_ident(name) && name.len() <= 64 && !is_atomic(name), "bad type name {name}");
        for (_, t) in fields {
            let (base, _) = split_array(t).with_context(|| format!("bad type {t}"))?;
            ensure!(is_atomic(base) || out.contains_key(base), "unknown type {t}");
        }
    }
    // 不允许递归类型（各家钱包算法不一致，ethers 直接拒绝）
    for name in out.keys() {
        let mut stack: Vec<&str> = out[name].iter().map(|(_, t)| base_of(t)).collect();
        let mut seen = BTreeSet::new();
        while let Some(t) = stack.pop() {
            if !out.contains_key(t) || !seen.insert(t) {
                continue;
            }
            ensure!(t != name, "typed-recursive");
            stack.extend(out[t].iter().map(|(_, t)| base_of(t)));
        }
    }
    Ok(out)
}

/// primary 引用到的所有结构体（不含 primary 自己），按名字排序。不递归（类型多也不会耗尽栈）
fn deps(types: &Types, primary: &str) -> Vec<String> {
    let mut found = BTreeSet::new();
    let mut stack = vec![primary.to_string()];
    while let Some(t) = stack.pop() {
        for (_, ft) in types.get(&t).map(Vec::as_slice).unwrap_or(&[]) {
            let b = base_of(ft);
            if b != primary && types.contains_key(b) && found.insert(b.to_string()) {
                stack.push(b.to_string());
            }
        }
    }
    found.into_iter().collect()
}

fn encode_type(types: &Types, primary: &str) -> String {
    std::iter::once(primary.to_string())
        .chain(deps(types, primary))
        .map(|n| {
            let fields: Vec<String> = types[&n].iter().map(|(f, t)| format!("{t} {f}")).collect();
            format!("{n}({})", fields.join(","))
        })
        .collect()
}

fn hash_struct(types: &Types, name: &str, value: &Value, depth: usize) -> Result<[u8; 32]> {
    ensure!(depth < 32, "typed data nested too deep");
    let obj = value.as_object().with_context(|| format!("{name} must be an object"))?;
    // 类型里没有的字段不会被签进去，却会被人看到：一律拒绝
    for k in obj.keys() {
        ensure!(types[name].iter().any(|(f, _)| f == k), "typed-extra-fields");
    }
    let mut buf = evm::keccak256(encode_type(types, name).as_bytes()).to_vec();
    for (field, t) in &types[name] {
        buf.extend_from_slice(&encode_value(types, t, obj.get(field).unwrap_or(&Value::Null), depth + 1)?);
    }
    Ok(evm::keccak256(&buf))
}

fn encode_value(types: &Types, t: &str, v: &Value, depth: usize) -> Result<[u8; 32]> {
    ensure!(depth < 32, "typed data nested too deep");
    if let Some(inner) = t.strip_suffix(']') {
        let (elem, len) = inner.rsplit_once('[').context("bad array type")?;
        let items = v.as_array().with_context(|| format!("{t} must be a list"))?;
        if !len.is_empty() {
            ensure!(len.parse::<usize>().ok() == Some(items.len()), "{t} needs exactly {len} items");
        }
        let mut buf = Vec::with_capacity(items.len() * 32);
        for it in items {
            buf.extend_from_slice(&encode_value(types, elem, it, depth + 1)?);
        }
        return Ok(evm::keccak256(&buf));
    }
    if types.contains_key(t) {
        return hash_struct(types, t, v, depth + 1);
    }
    let mut w = [0u8; 32];
    match t {
        "string" => return Ok(evm::keccak256(v.as_str().context("string expected")?.as_bytes())),
        "bytes" => return Ok(evm::keccak256(&parse_hex(v.as_str().context("bytes must be 0x hex")?)?)),
        // 只认 JSON 的 true/false：字符串 "false" 在各家钱包里解释不一样
        "bool" => w[31] = v.as_bool().context("bool expected")? as u8,
        "address" => w[12..].copy_from_slice(&parse_address_strict(v.as_str().context("address expected")?)?),
        _ if t.starts_with("bytes") => {
            let n: usize = t[5..].parse().context("bad bytesN")?;
            let b = parse_hex(v.as_str().context("bytesN must be 0x hex")?)?;
            ensure!(b.len() == n, "{t} needs {n} bytes");
            w[..n].copy_from_slice(&b);
        }
        _ if t.starts_with("uint") || t.starts_with("int") => {
            let signed = t.starts_with("int");
            let digits = &t[if signed { 3 } else { 4 }..];
            let bits = if digits.is_empty() { 256 } else { digits.parse().context("bad integer type")? };
            w = word_of_number(v, signed, bits)?;
        }
        _ => bail!("unknown type {t}"),
    }
    Ok(w)
}

// ---- 危险识别与给人看的签名内容

/// 字段名（小写）——地址类型（或地址数组）的这些字段 = 有人拿到权限或收款。按词头匹配（recipients、toAddress 也算）
const DANGER_ADDRESS_FIELDS: &[&str] = &[
    "to", "spender", "operator", "approved", "delegatee", "delegate", "taker", "allowed", "target", "recipient",
    "receiver", "beneficiary", "payee", "destination", "executor", "authorized",
];
/// 数额类字段（小写，按词头匹配：amounts、valueIn、startAmount 也算）
const AMOUNT_FIELDS: &[&str] = &["value", "amount", "wad", "allowance", "startamount", "endamount"];
/// 只包着一个哈希的字段（小写）：签的可能是任意内容的摘要（批量挂单、智能钱包代签）
const HASH_FIELDS: &[&str] = &["hash", "root", "digest", "safetxhash", "messagehash", "userophash"];
/// 挂单类的类型名：真正拿走资产的是「任何成交方」，签名里没有这个地址
const ORDER_TYPE_WORDS: &[&str] = &["order", "offer", "consideration", "listing", "bid"];

fn name_in(name: &str, list: &[&str]) -> bool {
    list.iter().any(|w| name == *w || (name.starts_with(w) && name.len() > w.len()))
}
/// 时间类字段（小写）：给数字加上日期
const TIME_FIELDS: &[&str] = &[
    "deadline", "expiry", "expiration", "expires", "validto", "validbefore", "validafter", "validuntil", "endtime",
    "starttime", "sigdeadline",
];
/// 类型名（小写）含这些词 = 危险
const DANGER_TYPE_WORDS: &[&str] = &[
    "permit", "order", "approv", "delegat", "transfer", "forward", "meta", "safetx", "execut", "withdraw", "authoriz",
    "swap", "bid", "offer", "listing", "consideration", "call", "trade", "spend", "payment", "allowance",
];

struct Ctx<'a> {
    types: &'a Types,
    /// 签名绑定的合约正好是 App 认识的代币：数额字段可以换算
    token: Option<&'static chain::TokenCfg>,
    danger: bool,
    /// 签名里所有收款/被授权地址（去重、不含本钱包）
    counterparties: Vec<String>,
    /// 本钱包地址（小写）
    me: String,
    /// 正在显示的数组字段名是收款/授权类
    array_danger: bool,
}

impl Ctx<'_> {
    fn add_party(&mut self, v: &Value) {
        if let Some(a) = v.as_str().map(str::to_ascii_lowercase) {
            if a != self.me && !self.counterparties.contains(&a) {
                self.counterparties.push(a);
            }
        }
    }
}

fn display_atomic(ctx: &Ctx, name: &str, t: &str, v: &Value) -> String {
    let lname = name.to_ascii_lowercase();
    match t {
        "string" => quoted(v.as_str().unwrap_or("")),
        "address" => v.as_str().unwrap_or("").to_ascii_lowercase(),
        "bool" => v.as_bool().unwrap_or(false).to_string(),
        "bytes" => {
            let s = v.as_str().unwrap_or("").to_ascii_lowercase();
            let mut out = s.clone();
            // 调用数据：用交易识别解析一下
            if let Ok(b) = parse_hex(&s) {
                if let Some(d) = decode_call(&b, [0u8; 20]) {
                    let amount = d.amount.map(|a| if is_unlimited(&a) { "∞".to_string() } else { word_to_decimal(&a) });
                    out.push_str(&format!(
                        "\n  ↳ {} → {}{}{}",
                        d.action,
                        hex0x(&d.counterparty),
                        amount.map(|a| format!(", {a}")).unwrap_or_default(),
                        if d.extra { " (+data)" } else { "" }
                    ));
                }
            }
            out
        }
        _ if t.starts_with("bytes") => v.as_str().unwrap_or("").to_ascii_lowercase(),
        _ => {
            let signed = t.starts_with("int");
            match word_of_number(v, signed, 256) {
                Ok(w) if signed && w[0] & 0x80 != 0 => {
                    let mut n = w;
                    for b in n.iter_mut() {
                        *b = !*b;
                    }
                    for b in n.iter_mut().rev() {
                        let (x, o) = b.overflowing_add(1);
                        *b = x;
                        if !o {
                            break;
                        }
                    }
                    format!("-{}", word_to_decimal(&n))
                }
                Ok(w) => {
                    let mut s = word_to_decimal(&w);
                    if name_in(&lname, AMOUNT_FIELDS) {
                        if is_unlimited(&w) {
                            s.push_str(" (∞)");
                        } else if let Some(tk) = ctx.token {
                            s.push_str(&format!(" (= {} {})", format_units(&w, tk.decimals), tk.symbol));
                        }
                    }
                    if TIME_FIELDS.contains(&lname.as_str()) && !is_unlimited(&w) {
                        let n = u128::from_be_bytes(w[16..].try_into().unwrap());
                        if (1_000_000_000..100_000_000_000).contains(&n) {
                            s.push_str(&format!(" ({})", utc_time(n as u64)));
                        }
                    }
                    s
                }
                Err(_) => quoted(&v.to_string()),
            }
        }
    }
}

fn render(ctx: &mut Ctx, t: &str, v: &Value, indent: usize, out: &mut String) {
    let pad = " ".repeat(indent);
    if let Some(inner) = t.strip_suffix(']') {
        let elem = inner.rsplit_once('[').map_or(inner, |(e, _)| e);
        let items = v.as_array().cloned().unwrap_or_default();
        if items.is_empty() {
            out.push_str(&format!("{pad}[]\n"));
        }
        if base_of(elem) == "bytes" {
            ctx.danger = true;
        }
        // 地址数组（recipients、to[]）：每个都可能收款
        if base_of(elem) == "address" && ctx.array_danger {
            ctx.danger = true;
            for it in &items {
                ctx.add_party(it);
            }
        }
        for (i, it) in items.iter().enumerate() {
            if ctx.types.contains_key(elem) || elem.ends_with(']') {
                out.push_str(&format!("{pad}[{i}]\n"));
                render(ctx, elem, it, indent + 2, out);
            } else {
                let shown = display_atomic(ctx, "", elem, it);
                out.push_str(&format!("{pad}[{i}] {shown}\n"));
            }
        }
        return;
    }
    let Some(fields) = ctx.types.get(t).cloned() else { return };
    for (name, ft) in &fields {
        let lname = name.to_ascii_lowercase();
        let fv = v.get(name).unwrap_or(&Value::Null);
        // 危险：收款/被授权方地址（含地址数组）、数额、任意调用数据、只包着一个哈希
        let party = name_in(&lname, DANGER_ADDRESS_FIELDS);
        if ft == "address" && party {
            ctx.danger = true;
            ctx.add_party(fv);
        }
        if (base_of(ft).starts_with("uint") || base_of(ft).starts_with("int")) && name_in(&lname, AMOUNT_FIELDS) {
            ctx.danger = true;
        }
        if base_of(ft) == "bytes" || matches!(lname.as_str(), "data" | "calldata" | "operation" | "functionsignature") {
            ctx.danger = true;
        }
        if ft == "bytes32" && HASH_FIELDS.contains(&lname.as_str()) {
            ctx.danger = true;
        }
        if ctx.types.contains_key(ft.as_str()) || ft.ends_with(']') {
            out.push_str(&format!("{pad}{name}:\n"));
            let was = ctx.array_danger;
            ctx.array_danger = party;
            render(ctx, ft, fv, indent + 2, out);
            ctx.array_danger = was;
        } else {
            let shown = display_atomic(ctx, name, ft, fv);
            out.push_str(&format!("{pad}{name}: {shown}\n"));
        }
    }
}

/// EIP712Domain 只认这 5 个字段，类型也必须是标准的
const DOMAIN_FIELDS: &[(&str, &str)] = &[
    ("name", "string"),
    ("version", "string"),
    ("chainId", "uint256"),
    ("verifyingContract", "address"),
    ("salt", "bytes32"),
];

struct TypedData {
    digest: [u8; 32],
    summary: DappTypedData,
}

/// 解析并算出 EIP-712 摘要。拒绝：太大、递归类型、domain 和 EIP712Domain 类型对不上或用了非标准字段/类型、
/// 不带 chainId、chainId 不是当前链、有类型里没定义的字段、v3 里用数组、各种格式不严格的值
fn typed_data(json: &str, chain_id: u64, version: u32, me: &str) -> Result<TypedData> {
    ensure!(json.len() <= MAX_TYPED_JSON, "typed-too-large");
    let v: Value = serde_json::from_str(json).context("typed data is not JSON")?;
    let mut types = parse_types(&v["types"])?;
    let domain = v["domain"].as_object().context("domain missing")?.clone();
    let primary = v["primaryType"].as_str().context("primaryType missing")?.to_string();
    ensure!(primary != "EIP712Domain", "signing the domain itself is not supported");
    ensure!(types.contains_key(&primary), "primaryType {primary} is not defined");
    if version == 3 {
        ensure!(types.values().flatten().all(|(_, t)| !t.contains('[')), "v3-no-arrays");
    }
    ensure!(domain.keys().all(|k| DOMAIN_FIELDS.iter().any(|(f, _)| f == k)), "domain-mismatch");
    match types.get("EIP712Domain") {
        // 声明了的：字段必须和 domain 的键一模一样、类型必须是标准类型，否则「显示的」和「签的」对不上
        Some(dt) => {
            let declared: BTreeSet<&str> = dt.iter().map(|(n, _)| n.as_str()).collect();
            let present: BTreeSet<&str> = domain.keys().map(String::as_str).collect();
            ensure!(declared == present, "domain-mismatch");
            for (n, t) in dt {
                ensure!(DOMAIN_FIELDS.iter().any(|(f, ft)| f == n && ft == t), "domain-mismatch");
            }
        }
        None => {
            types.insert(
                "EIP712Domain".into(),
                DOMAIN_FIELDS.iter().filter(|(f, _)| domain.contains_key(*f)).map(|(f, t)| (f.to_string(), t.to_string())).collect(),
            );
        }
    }
    // 必须绑定链号，而且是当前链
    let c = domain.get("chainId").context("domain-no-chain")?;
    let want = word_of_number(&json!(chain_id.to_string()), false, 256)?;
    ensure!(word_of_number(c, false, 256)? == want, "chain-mismatch");
    let domain_hash = hash_struct(&types, "EIP712Domain", &Value::Object(domain.clone()), 0)?;
    let message_hash = hash_struct(&types, &primary, &v["message"], 0)?;
    let digest = evm::keccak256(&[&[0x19u8, 0x01][..], &domain_hash, &message_hash].concat());

    let verifying_contract = domain.get("verifyingContract").and_then(Value::as_str).unwrap_or("").to_ascii_lowercase();
    let token = chain::TOKENS
        .iter()
        .find(|t| t.chain == chain_id && !verifying_contract.is_empty() && t.address.is_some_and(|a| a.eq_ignore_ascii_case(&verifying_contract)));
    let lower = primary.to_ascii_lowercase();
    let mut ctx = Ctx {
        types: &types,
        token,
        danger: DANGER_TYPE_WORDS.iter().any(|k| lower.contains(k)),
        counterparties: Vec::new(),
        me: me.to_ascii_lowercase(),
        array_danger: false,
    };
    let mut fields = String::new();
    render(&mut ctx, &primary, &v["message"], 0, &mut fields);
    // domain 里名字、合约以外的字段也列出来
    for key in ["version", "salt"] {
        if let Some(x) = domain.get(key) {
            let shown = match x {
                Value::String(s) if key == "version" => quoted(s),
                Value::String(s) => s.to_ascii_lowercase(),
                other => quoted(&other.to_string()),
            };
            fields.push_str(&format!("domain.{key}: {shown}\n"));
        }
    }
    Ok(TypedData {
        digest,
        summary: DappTypedData {
            domain_name: oneline(domain.get("name").and_then(Value::as_str).unwrap_or(""), 60),
            verifying_contract,
            chain_id: chain_id.to_string(),
            primary_type: primary,
            fields: fields.trim_end().to_string(),
            danger: ctx.danger,
            // 挂单类：拿走资产的是「任何成交方」，签名里的收款地址（通常是自己）不能当成对方
            counterparty: if ORDER_TYPE_WORDS.iter().any(|k| lower.contains(k)) || ctx.counterparties.is_empty() {
                None
            } else {
                Some(ctx.counterparties.join(", "))
            },
        },
    })
}

// ---------------- personal_sign ----------------

/// 看起来能读的字符：字母数字、标点、空白、常用符号区、中日韩标点与全角字符
fn readable(c: char) -> bool {
    c.is_alphanumeric()
        || c.is_ascii_punctuation()
        || c.is_whitespace()
        || matches!(c as u32, 0x00a1..=0x00bf | 0x2000..=0x2bff | 0x3000..=0x303f | 0xfe0f | 0xff00..=0xffef | 0x1f000..=0x1faff)
}

fn message_of(raw: &str) -> DappMessage {
    let is_hex = raw.len() >= 2 && raw.starts_with("0x") && raw[2..].len() % 2 == 0 && raw[2..].bytes().all(|b| b.is_ascii_hexdigit());
    let bytes = if is_hex { hex::decode(&raw[2..]).unwrap_or_default() } else { raw.as_bytes().to_vec() };
    let hex = hex0x(&bytes);
    let text = std::str::from_utf8(&bytes).ok();
    let mut blind = bytes.is_empty() || text.is_none() || (is_hex && bytes.len() == 32);
    if let Some(t) = text {
        // 恰好是合法 UTF-8 的乱码、夹着大量看不见字符的内容，也当看不懂
        let total = t.chars().count();
        let ok = t.chars().filter(|c| readable(*c) && (!is_hidden(*c) || *c as u32 == 0x200d || *c as u32 == 0xfe0f)).count();
        if total >= 8 && ok * 10 < total * 7 {
            blind = true;
        }
    }
    let (shown, cut) = match text {
        Some(t) if !blind => multiline(t, MAX_MESSAGE_LINES),
        _ => (hex.clone(), false),
    };
    DappMessage { text: shown, hex, blind: blind || cut }
}

// ---------------- 交易 ----------------

struct DappCall {
    call: Call,
    gas_hint: Option<u128>,
}

fn parse_tx(json: &str, me: &str, chain_id: u64) -> Result<DappCall> {
    let v: Value = serde_json::from_str(json).context("transaction is not JSON")?;
    if let Some(from) = v.get("from").and_then(Value::as_str) {
        ensure!(from.eq_ignore_ascii_case(me), "from is not this wallet");
    }
    if let Some(c) = v.get("chainId").filter(|c| !c.is_null()) {
        ensure!(quantity_of(c)? == u128::from(chain_id), "chain-mismatch");
    }
    let to = match v.get("to").and_then(Value::as_str) {
        Some(t) if !t.is_empty() => parse_address_strict(t)?,
        _ => bail!("no-contract-creation"),
    };
    let num = |k: &str| -> Result<Option<u128>> {
        match v.get(k) {
            None | Some(Value::Null) => Ok(None),
            Some(x) => quantity_of(x).map(Some),
        }
    };
    // data 为 null 时看 input（有的库两个都填）
    let data = match ["data", "input"].iter().find_map(|k| v.get(*k).and_then(Value::as_str)) {
        Some(d) if d != "0x" && !d.is_empty() => parse_hex(d)?,
        _ => Vec::new(),
    };
    Ok(DappCall {
        call: Call { to, value: num("value")?.unwrap_or(0), data },
        gas_hint: num("gas")?.or(num("gasLimit")?),
    })
}

/// 识别出的合约调用（approve 是 ERC-20 还是 NFT，要查链以后再定）
#[derive(Debug, PartialEq)]
struct Decoded {
    action: &'static str,
    counterparty: [u8; 20],
    from: Option<[u8; 20]>,
    /// 代币合约（Permit2 是参数里的 token，其余是交易的 to）
    token: Option<[u8; 20]>,
    amount: Option<[u8; 32]>,
    danger: bool,
    /// 调用数据比需要的长
    extra: bool,
    /// Permit2 授权的到期时间（秒）
    expiry: Option<u64>,
}

fn word_at(data: &[u8], i: usize) -> Option<[u8; 32]> {
    data.get(4 + 32 * i..4 + 32 * (i + 1)).map(|s| s.try_into().unwrap())
}

fn addr_at(data: &[u8], i: usize) -> Option<[u8; 20]> {
    let w = word_at(data, i)?;
    w[..12].iter().all(|b| *b == 0).then(|| w[12..].try_into().unwrap())
}

/// 布尔参数：整个字必须正好是 0 或 1（老编译器把任何非 0 都当 true，只看最后一个字节会把「授权」认成「撤销」）
fn bool_at(data: &[u8], i: usize) -> Option<bool> {
    let w = word_at(data, i)?;
    if w[..31].iter().any(|b| *b != 0) {
        return None;
    }
    match w[31] {
        0 => Some(false),
        1 => Some(true),
        _ => None,
    }
}

/// 常见的代币授权/转账调用。参数格式不对的不认（当作无法识别 = 危险）；
/// 参数后面多出来的数据合约会忽略，所以照样识别，但标危险
fn decode_call(data: &[u8], to: [u8; 20]) -> Option<Decoded> {
    let sel: [u8; 4] = data.get(..4)?.try_into().ok()?;
    let is = |sig: &str| evm::selector(sig) == sel;
    let need = |n: usize| data.len() >= 4 + 32 * n;
    let extra = |n: usize| data.len() > 4 + 32 * n;
    if is("approve(address,uint256)") && need(2) {
        // ERC-20 还是 NFT（同一个函数编号）由 plan_dapp_tx 查链决定
        return Some(Decoded { action: "approve", counterparty: addr_at(data, 0)?, from: None, token: Some(to), amount: Some(word_at(data, 1)?), danger: true, extra: extra(2), expiry: None });
    }
    if is("increaseAllowance(address,uint256)") && need(2) {
        return Some(Decoded { action: "increase-allowance", counterparty: addr_at(data, 0)?, from: None, token: Some(to), amount: Some(word_at(data, 1)?), danger: true, extra: extra(2), expiry: None });
    }
    if is("transfer(address,uint256)") && need(2) {
        return Some(Decoded { action: "transfer", counterparty: addr_at(data, 0)?, from: None, token: Some(to), amount: Some(word_at(data, 1)?), danger: extra(2), extra: extra(2), expiry: None });
    }
    if is("transferFrom(address,address,uint256)") && need(3) {
        return Some(Decoded { action: "transfer-from", counterparty: addr_at(data, 1)?, from: Some(addr_at(data, 0)?), token: Some(to), amount: Some(word_at(data, 2)?), danger: extra(3), extra: extra(3), expiry: None });
    }
    if is("setApprovalForAll(address,bool)") && need(2) {
        let on = bool_at(data, 1)?;
        return Some(Decoded {
            action: if on { "approval-for-all" } else { "revoke-all" },
            counterparty: addr_at(data, 0)?,
            from: None,
            token: Some(to),
            amount: None,
            danger: on || extra(2),
            extra: extra(2),
            expiry: None,
        });
    }
    // Permit2：approve(address token, address spender, uint160 amount, uint48 expiration)
    if is("approve(address,address,uint160,uint48)") && need(4) {
        let exp = word_at(data, 3)?;
        let expiry = exp[..24].iter().all(|b| *b == 0).then(|| u64::from_be_bytes(exp[24..].try_into().unwrap()));
        return Some(Decoded { action: "permit2-approve", counterparty: addr_at(data, 1)?, from: None, token: Some(addr_at(data, 0)?), amount: Some(word_at(data, 2)?), danger: true, extra: extra(4), expiry });
    }
    None
}

/// 只读请求和交易估算按网站限频：额度用完时返回要等多久（网页看到的只是慢一点，不是一个特有的错误）
fn rate_wait(origin: &str) -> Duration {
    static BUCKETS: OnceLock<Mutex<HashMap<String, (f64, Instant)>>> = OnceLock::new();
    let mut map = BUCKETS.get_or_init(Default::default).lock().unwrap();
    let now = Instant::now();
    if map.len() > 500 {
        // 只清掉一分钟没动静的网站（它们的额度早就攒满了，清掉也不影响）
        map.retain(|_, (_, t)| now.duration_since(*t) < Duration::from_secs(60));
    }
    let e = map.entry(origin.to_string()).or_insert((RATE_BURST, now));
    let tokens = (e.0 + now.duration_since(e.1).as_secs_f64() * RATE_PER_SEC).min(RATE_BURST);
    e.1 = now;
    // 先扣再说（可以欠），欠多少就要等多久
    e.0 = tokens - 1.0;
    if e.0 >= 0.0 {
        Duration::ZERO
    } else {
        Duration::from_secs_f64(-e.0 / RATE_PER_SEC)
    }
}

/// 等到有额度；要等超过 5 秒（网页在狂刷）就报限频
async fn rate_limit(origin: &str) -> Result<()> {
    let wait = rate_wait(origin);
    ensure!(wait <= Duration::from_secs(5), "rate-limited");
    if !wait.is_zero() {
        tokio::time::sleep(wait).await;
    }
    Ok(())
}

/// eth_getLogs 必须带合约地址；区块范围必须写成数字且不超过 LOGS_MAX_BLOCKS（或者指定 blockHash 只查一个块）
fn check_get_logs(params: &Value) -> Result<()> {
    let f = params.get(0).and_then(Value::as_object).context("getlogs-range")?;
    let has_address = match f.get("address") {
        Some(Value::String(s)) => !s.is_empty(),
        Some(Value::Array(a)) => !a.is_empty() && a.len() <= 10,
        _ => false,
    };
    ensure!(has_address, "getlogs-range");
    if f.get("blockHash").is_some_and(|h| h.is_string()) {
        return Ok(());
    }
    let block = |k: &str| f.get(k).and_then(Value::as_str).and_then(|s| s.strip_prefix("0x")).and_then(|h| u64::from_str_radix(h, 16).ok());
    match (block("fromBlock"), block("toBlock")) {
        (Some(a), Some(b)) if b >= a && b - a <= LOGS_MAX_BLOCKS => Ok(()),
        _ => bail!("getlogs-range"),
    }
}

/// 确认票据：一次性随机值，登记「链号 + 交易内容 + 手续费」。发送时取出并作废；同一张票据只能发一次
fn tickets() -> &'static Mutex<HashMap<String, (u64, [u8; 32], String, Instant)>> {
    static T: OnceLock<Mutex<HashMap<String, (u64, [u8; 32], String, Instant)>>> = OnceLock::new();
    T.get_or_init(Default::default)
}

fn issue_ticket(chain_id: u64, tx_json: &str, fee: &str) -> String {
    let id = hex0x(&rand::random::<[u8; 16]>());
    let mut t = tickets().lock().unwrap();
    // 十分钟没用的作废
    t.retain(|_, v| v.3.elapsed() < Duration::from_secs(600));
    t.insert(id.clone(), (chain_id, evm::keccak256(tx_json.as_bytes()), fee.to_string(), Instant::now()));
    id
}

fn take_ticket(id: &str, chain_id: u64, tx_json: &str, fee: &str) -> bool {
    match tickets().lock().unwrap().remove(id) {
        Some((c, h, f, at)) => c == chain_id && h == evm::keccak256(tx_json.as_bytes()) && f == fee && at.elapsed() < Duration::from_secs(600),
        None => false,
    }
}

impl Account {
    /// 代币的小数位、名字、名字是不是 App 自己认识的：先查代币表，不认识的读合约（读不到就是 None）
    async fn token_info(&self, rpc: &Rpc, chain_id: u64, token: [u8; 20]) -> (Option<u8>, Option<String>, bool) {
        let addr = hex0x(&token);
        if let Some(t) = chain::TOKENS.iter().find(|t| t.chain == chain_id && t.address.is_some_and(|a| a.eq_ignore_ascii_case(&addr))) {
            return (Some(t.decimals), Some(t.symbol.to_string()), true);
        }
        let decimals = rpc.uint(token, "decimals()", &[]).await.ok().filter(|d| *d <= 36).map(|d| d as u8);
        let symbol = async {
            let out = rpc.call(token, "symbol()", &[]).await.ok()?;
            let s = evm::dynamic(&out, 0).ok()?;
            let s = std::str::from_utf8(s).ok()?;
            (!s.is_empty() && s.len() <= 16).then(|| oneline(s, 16))
        }
        .await;
        (decimals, symbol, false)
    }

    /// 合约是不是 NFT（ERC-721，靠 ERC-165 查询）
    async fn is_nft(&self, rpc: &Rpc, token: [u8; 20]) -> bool {
        let mut id = [0u8; 32];
        id[..4].copy_from_slice(&[0x80, 0xac, 0x58, 0xcd]);
        rpc.call(token, "supportsInterface(bytes4)", &[Abi::Bytes32(id)])
            .await
            .ok()
            .and_then(|out| out.get(..32).map(|w| w[31] == 1 && w[..31].iter().all(|b| *b == 0)))
            .unwrap_or(false)
    }

    /// 估算一笔网页交易：gas、手续费、余额是否够、识别合约操作。估算失败（合约会回滚）就把节点的说明放进 error
    async fn plan_dapp_tx(&self, chain_id: u64, tx_json: &str) -> Result<(DappTx, Option<(Call, u128, u128)>)> {
        let rpc = Rpc::new(&self.http(), chain_id)?;
        let me = self.keys.id.address().to_string();
        let cfg = rpc.cfg;
        let mut out = DappTx {
            chain_id,
            chain_name: cfg.name.into(),
            to: String::new(),
            to_is_contract: false,
            value: "0".into(),
            symbol: cfg.native.into(),
            data_len: 0,
            selector: None,
            action: None,
            counterparty: None,
            from: None,
            token: None,
            token_amount: None,
            token_symbol: None,
            token_known: false,
            unlimited: false,
            expiry: None,
            nft: false,
            raw_amount: false,
            extra_data: false,
            danger: false,
            gas: "0".into(),
            fee: "0".into(),
            balance: "0".into(),
            ticket: String::new(),
            error: None,
        };
        let p = match parse_tx(tx_json, &me, chain_id) {
            Ok(p) => p,
            Err(e) => {
                out.error = Some(e.to_string());
                return Ok((out, None));
            }
        };
        out.to = hex0x(&p.call.to);
        out.value = format_amount(p.call.value, 18);
        out.data_len = p.call.data.len() as u32;
        out.selector = (p.call.data.len() >= 4).then(|| hex0x(&p.call.data[..4]));
        let (balance, price, code) = tokio::try_join!(
            rpc.native_balance(&me),
            rpc.gas_price(),
            rpc.request("eth_getCode", json!([out.to, "latest"]))
        )?;
        out.to_is_contract = code.as_str().is_some_and(|c| c.len() > 2);
        out.balance = format_amount(balance, 18);
        // 有调用数据的：默认当危险，识别出来是无害的才放下
        out.danger = !p.call.data.is_empty();
        if let Some(d) = decode_call(&p.call.data, p.call.to) {
            out.counterparty = Some(hex0x(&d.counterparty));
            out.from = d.from.map(|f| hex0x(&f));
            out.extra_data = d.extra;
            let mut action = d.action;
            let mut danger = d.danger;
            if let Some(token) = d.token {
                out.token = Some(hex0x(&token));
                let (decimals, symbol, known) = self.token_info(&rpc, chain_id, token).await;
                out.token_symbol = symbol;
                out.token_known = known;
                // approve / transferFrom 的函数编号 ERC-20 和 NFT 一样：认识的代币、或读得到小数位又不是 NFT 的才按代币算
                let nft = matches!(action, "approve" | "transfer-from") && !known && (decimals.is_none() || self.is_nft(&rpc, token).await);
                if let Some(a) = d.amount {
                    if nft {
                        out.nft = true;
                        out.token_amount = Some(word_to_decimal(&a));
                        action = if action == "approve" { "nft-approve" } else { "nft-transfer-from" };
                        // NFT 授权、转出都标危险
                        danger = true;
                    } else if action == "approve" && a == [0u8; 32] && decimals.is_some() {
                        // 普通代币授权 0 = 撤销授权
                        action = "revoke";
                        danger = d.extra;
                    } else {
                        out.unlimited = is_unlimited(&a);
                        if !out.unlimited {
                            out.token_amount = Some(match decimals {
                                Some(dec) => format_units(&a, dec),
                                None => {
                                    out.raw_amount = true;
                                    word_to_decimal(&a)
                                }
                            });
                        }
                        // 小数位读不到：不知道是什么合约，按危险
                        if decimals.is_none() {
                            danger = true;
                        }
                    }
                }
            }
            // 从自己账上转出一个 App 不认识的代币：标危险（可能是任何东西）
            if action == "transfer-from" && !out.token_known && d.from.is_some_and(|f| hex0x(&f).eq_ignore_ascii_case(&me)) {
                danger = true;
            }
            out.expiry = d.expiry.map(utc_time);
            out.action = Some(action.into());
            out.danger = danger || d.extra;
        }
        // 合约调用同时转出原生币：一律标危险（「帮你撤销授权」类钓鱼会顺手要钱）
        if !p.call.data.is_empty() && p.call.value > 0 {
            out.danger = true;
        }
        // 余额连转出的金额都不够，不用问节点（节点的报错是英文原话，用户看不懂）
        if balance < p.call.value {
            out.error = Some("insufficient-balance".into());
            return Ok((out, None));
        }
        let estimate = match rpc.estimate(&me, &p.call).await {
            Ok(g) => g,
            Err(e) if e.to_string().to_ascii_lowercase().contains("insufficient funds") => {
                out.error = Some("insufficient-gas".into());
                return Ok((out, None));
            }
            Err(e) => {
                // 合约返回的说明可以写任何话（包括冒充 App 的提示）：单行、截短、标出看不见的字符，弹窗上标「合约返回」
                out.error = Some(format!("will-fail: {}", oneline(&e.to_string(), 160)));
                return Ok((out, None));
            }
        };
        // 网页给的 gas 上限比估算多时，最多用到估算的 2 倍（防止网页把手续费刷高、烧掉余额）
        let gas = match p.gas_hint {
            Some(h) if h > estimate => h.min(estimate.saturating_mul(2)),
            _ => estimate,
        };
        let Some(fee) = gas.checked_mul(price) else {
            out.error = Some("invalid-fee".into());
            return Ok((out, None));
        };
        out.gas = gas.to_string();
        out.fee = format_amount(fee, 18);
        out.ticket = issue_ticket(chain_id, tx_json, &out.fee);
        match p.call.value.checked_add(fee) {
            Some(total) if total <= balance => Ok((out, Some((p.call, gas, price)))),
            _ => {
                out.error = Some("insufficient-gas".into());
                Ok((out, None))
            }
        }
    }
}

#[uniffi::export]
impl Account {
    /// 网页的只读请求（JSON-RPC）。origin 是网站来源（限频用）；params_json 是参数数组的 JSON，返回结果的 JSON
    pub async fn dapp_rpc(self: Arc<Self>, chain_id: u64, origin: String, method: String, params_json: String) -> Result<String, CoreError> {
        crate::run(async move {
            let cfg = chain::chain(chain_id)?;
            let result = match method.as_str() {
                "eth_chainId" => json!(format!("0x{:x}", cfg.id)),
                "net_version" => json!(cfg.id.to_string()),
                m if READ_METHODS.contains(&m) => {
                    rate_limit(&origin).await?;
                    let params: Value = serde_json::from_str(&params_json).unwrap_or(json!([]));
                    ensure!(params.is_array(), "params must be a list");
                    if m == "eth_getLogs" {
                        check_get_logs(&params)?;
                    }
                    Rpc::new(&self.http(), chain_id)?.request(m, params).await?
                }
                m => bail!("unsupported-method: {m}"),
            };
            Ok(serde_json::to_string(&result)?)
        })
        .await
    }

    /// 网页要发的交易：给确认弹窗看的信息。和只读请求共用每个网站的限频额度
    pub async fn dapp_check_tx(self: Arc<Self>, chain_id: u64, origin: String, tx_json: String) -> Result<DappTx, CoreError> {
        crate::run(async move {
            rate_limit(&origin).await?;
            Ok(self.plan_dapp_tx(chain_id, &tx_json).await?.0)
        })
        .await
    }

    /// 用户确认后发出。chain_id、tx_json、confirmed_fee、ticket 必须原样来自确认弹窗那一次 dapp_check_tx；
    /// 重新估算后手续费涨了一半以上就报 fee-changed。签名后、广播前先记进钱包的「待确认」列表
    pub async fn dapp_send_tx(self: Arc<Self>, chain_id: u64, tx_json: String, confirmed_fee: String, ticket_in: String) -> Result<String, CoreError> {
        crate::run(async move {
            ensure!(take_ticket(&ticket_in, chain_id, &tx_json, &confirmed_fee), "ticket-mismatch");
            let _guard = self.tx_lock.lock().await;
            let (est, plan) = self.plan_dapp_tx(chain_id, &tx_json).await?;
            if let Some(e) = est.error {
                bail!("{e}");
            }
            let (call, gas, price) = plan.context("not sendable")?;
            let confirmed = chain::parse_amount(&confirmed_fee, 18).context("invalid-fee")?;
            let fresh = chain::parse_amount(&est.fee, 18).context("invalid-fee")?;
            ensure!(fresh.saturating_mul(2) <= confirmed.saturating_mul(3), "fee-changed");
            let rpc = Rpc::new(&self.http(), chain_id)?;
            let tx = rpc.sign(&self.keys.id, &call, gas, price).await?;
            // 和钱包自己的转账一样：先记「待确认」再广播；确定没发出去才删掉
            let native = chain::TOKENS.iter().find(|t| t.chain == chain_id && t.address.is_none());
            if let Some(t) = native {
                let record = WalletTx {
                    chain_id,
                    token_id: t.id(),
                    to: hex0x(&call.to),
                    amount: format_amount(call.value, t.decimals),
                    tx_hash: tx.hash.clone(),
                    created_at: now_ms(),
                    dapp: true,
                };
                self.update_pending(|l| l.push(record))?;
            }
            if let Err(e) = rpc.broadcast(&tx).await {
                // 说不清有没有发出去：记录留着（钱包里能看到），只有确定没发出去才删
                if e.to_string() != chain::MAYBE_SENT {
                    self.update_pending(|l| l.retain(|r| r.tx_hash != tx.hash))?;
                }
                return Err(e);
            }
            debug!("dapp transaction sent on {}", rpc.cfg.name);
            Ok(tx.hash)
        })
        .await
    }

    /// personal_sign 的预览：原生层先拿它给用户看，用户确认后把其中的 hex 交给 dapp_personal_sign
    pub fn dapp_message(&self, raw: String) -> DappMessage {
        message_of(&raw)
    }

    /// personal_sign：message_hex 是消息的原始字节（0x 十六进制）。返回 65 字节签名
    pub fn dapp_personal_sign(&self, message_hex: String) -> Result<String, CoreError> {
        let msg = parse_hex(&message_hex)?;
        Ok(sign_digest(&self.keys.id, &personal_digest(&msg)))
    }

    /// eth_signTypedData 的确认信息（解析失败、链不对、domain 对不上、有多余字段都在这里报错）。version 是 3 或 4
    pub fn dapp_typed_data(&self, chain_id: u64, json: String, version: u32) -> Result<DappTypedData, CoreError> {
        Ok(typed_data(&json, chain_id, version, self.keys.id.address())?.summary)
    }

    pub fn dapp_sign_typed_data(&self, chain_id: u64, json: String, version: u32) -> Result<String, CoreError> {
        Ok(sign_digest(&self.keys.id, &typed_data(&json, chain_id, version, self.keys.id.address())?.digest))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const ME: &str = "0x2c7536E3605D9C16a7a3D7b1898e529396a65c23";

    fn key() -> Identity {
        let raw: [u8; 32] = hex::decode("4c0883a69102937d6231471b5dbb6204fe5129617082792ae468d01a3f362318").unwrap().try_into().unwrap();
        Identity::new(&raw, "relay1.taperelay.com:4246").unwrap()
    }

    // 参考答案由 ethers v6 生成（Wallet.signMessage / TypedDataEncoder.hash / Wallet.signTypedData）
    #[test]
    fn personal_sign_matches_ethers() {
        let id = key();
        assert!(id.address().eq_ignore_ascii_case("0x2c7536E3605D9C16a7a3D7b1898e529396a65c23"));
        let d = personal_digest(b"hello TapeTape");
        assert_eq!(hex0x(&d), "0x7c0714b799675867f000957bb55fb638a98e225d4b9b7e64184edc171e2f5a56");
        assert_eq!(sign_digest(&id, &d), "0x4e38841bb6181e095aa6e1de1641306d8f4c684af30370fb4eb7a029dbc1695126c6b037d991a47edfeee3309bf1efd81d115a34837b786e53785921aa68c2531b");
        let d = personal_digest(&[0xde, 0xad, 0xbe, 0xef]);
        assert_eq!(sign_digest(&id, &d), "0x27c9251b613f25a4d52f5375c0be39ea8b8f5bb46a29034620f613d71520ac24034d84dcd3e6251af82cff1bda9c38141a238abaa1830bd8ca064474b60c641f1c");
    }

    const MAIL: &str = r#"{"types":{"EIP712Domain":[{"name":"name","type":"string"},{"name":"version","type":"string"},{"name":"chainId","type":"uint256"},{"name":"verifyingContract","type":"address"}],"Person":[{"name":"name","type":"string"},{"name":"wallet","type":"address"}],"Mail":[{"name":"from","type":"Person"},{"name":"to","type":"Person"},{"name":"contents","type":"string"}]},"primaryType":"Mail","domain":{"name":"Ether Mail","version":"1","chainId":1,"verifyingContract":"0xCcCCccccCCCCcCCCCCCcCcCccCcCCCcCcccccccC"},"message":{"from":{"name":"Cow","wallet":"0xCD2a3d9F938E13CD947Ec05AbC7FE734Df8DD826"},"to":{"name":"Bob","wallet":"0xbBbBBBBbbBBBbbbBbbBbbbbBBbBbbbbBbBbbBBbB"},"contents":"Hello, Bob!"}}"#;

    #[test]
    fn typed_data_spec_example() {
        let t = typed_data(MAIL, 1, 4, ME).unwrap();
        assert_eq!(hex0x(&t.digest), "0xbe609aee343fb3c4b28e1df9e632fca64fcfaede20f02e86244efddf30957bd2");
        assert_eq!(sign_digest(&key(), &t.digest), "0x19e4c5dcf77c10f4b30df3d4cb5822a99a69ec18e6f5d7b9a889a5a4815740732656ebdcc5a9c8eee63e684a5ed282d6b59ffe2914076b025a77df76559403111c");
        assert_eq!(t.summary.primary_type, "Mail");
        assert_eq!(t.summary.domain_name, "Ether Mail");
        // 显示的是按类型定义顺序的实际签名字段，字符串按 JSON 转义；domain 的 version 列在最后
        assert_eq!(t.summary.fields, "from:\n  name: \"Cow\"\n  wallet: 0xcd2a3d9f938e13cd947ec05abc7fe734df8dd826\nto:\n  name: \"Bob\"\n  wallet: 0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\ncontents: \"Hello, Bob!\"\ndomain.version: \"1\"");
        assert!(!t.summary.danger);
        assert_eq!(typed_data(MAIL, 196, 4, ME).err().unwrap().to_string(), "chain-mismatch");
        // 省略 EIP712Domain 类型的写法，按 domain 字段补出来，摘要相同
        let mut v: Value = serde_json::from_str(MAIL).unwrap();
        v["types"].as_object_mut().unwrap().remove("EIP712Domain");
        assert_eq!(hex0x(&typed_data(&v.to_string(), 1, 4, ME).unwrap().digest), "0xbe609aee343fb3c4b28e1df9e632fca64fcfaede20f02e86244efddf30957bd2");
    }

    #[test]
    fn typed_data_arrays_ints_bytes() {
        let j = json!({
            "types": {
                "EIP712Domain": [{"name":"name","type":"string"},{"name":"version","type":"string"},{"name":"chainId","type":"uint256"},{"name":"verifyingContract","type":"address"},{"name":"salt","type":"bytes32"}],
                "Item": [{"name":"id","type":"uint256"},{"name":"tags","type":"string[]"},{"name":"delta","type":"int256"},{"name":"small","type":"uint8"}],
                "Order": [{"name":"owner","type":"address"},{"name":"items","type":"Item[]"},{"name":"flag","type":"bool"},{"name":"blob","type":"bytes"},{"name":"tag","type":"bytes32"},{"name":"nums","type":"uint256[2]"},{"name":"deadline","type":"uint64"}]
            },
            "primaryType": "Order",
            "domain": {"name":"TapeCode","version":"2","chainId":196,"verifyingContract":"0x1111111111111111111111111111111111111111","salt": format!("0x{}", "22".repeat(32))},
            "message": {"owner":"0x2222222222222222222222222222222222222222","items":[{"id":"115792089237316195423570985008687907853269984665640564039457584007913129639935","tags":["a","中文"],"delta":"-5","small":7},{"id":"0x10","tags":[],"delta":12,"small":"255"}],"flag":true,"blob":"0x0102ff","tag": format!("0x{}", "ab".repeat(32)),"nums":["1",2],"deadline":1800000000u64}
        });
        let t = typed_data(&j.to_string(), 196, 4, ME).unwrap();
        assert_eq!(hex0x(&t.digest), "0xe9ffa08cba9ae3843965adb7a9bf73f49f6afd3ac484d0ce2e1c18175b5ae2e7");
        assert_eq!(sign_digest(&key(), &t.digest), "0xb386cad67c10f5a126847d17ef20d92cfb744633e8e42378c29293193ba85a0a2ce44975f6e4189fb66aab20552859883e1a603a17d04b318262a4cc3ebb6cc21c");
        assert!(t.summary.fields.contains("delta: -5"));
        assert!(t.summary.fields.contains("deadline: 1800000000 (2027-01-15 08:00 UTC)"), "{}", t.summary.fields);
        assert!(t.summary.fields.contains("domain.salt: 0x2222"));
        assert!(t.summary.danger, "Order 类型要标危险");
        // v3 不支持数组
        assert_eq!(typed_data(&j.to_string(), 196, 3, ME).err().unwrap().to_string(), "v3-no-arrays");
    }

    fn permit(extra: impl FnOnce(&mut Value)) -> Value {
        let mut v = json!({
            "types": {
                "EIP712Domain": [{"name":"name","type":"string"},{"name":"chainId","type":"uint256"},{"name":"verifyingContract","type":"address"}],
                "Permit": [{"name":"owner","type":"address"},{"name":"spender","type":"address"},{"name":"value","type":"uint256"},{"name":"nonce","type":"uint256"},{"name":"deadline","type":"uint256"}]
            },
            "primaryType": "Permit",
            "domain": {"name":"USDT","chainId":196,"verifyingContract":"0x1111111111111111111111111111111111111111"},
            "message": {"owner":"0x2222222222222222222222222222222222222222","spender":"0x3333333333333333333333333333333333333333","value":"1000","nonce":0,"deadline":1}
        });
        extra(&mut v);
        v
    }

    #[test]
    fn typed_data_rejects_tricks() {
        // 正常的 Permit：危险标记、spender 显示出来并作为再确认的对象
        let t = typed_data(&permit(|_| {}).to_string(), 196, 4, ME).unwrap();
        assert!(t.summary.danger);
        assert!(t.summary.fields.contains("spender: 0x3333333333333333333333333333333333333333"));
        assert_eq!(t.summary.counterparty.as_deref(), Some("0x3333333333333333333333333333333333333333"));
        // 塞一个类型里没有的超长字段，想把 spender 挤出屏幕 → 拒绝
        let v = permit(|v| v["message"]["aaaa"] = json!("x".repeat(3000)));
        assert_eq!(typed_data(&v.to_string(), 196, 4, ME).err().unwrap().to_string(), "typed-extra-fields");
        // EIP712Domain 类型里故意不写 chainId，domain 里却写了 → 拒绝（否则签名不绑链号、可跨链重放）
        let v = permit(|v| { v["types"]["EIP712Domain"] = json!([{"name":"name","type":"string"},{"name":"verifyingContract","type":"address"}]); });
        assert_eq!(typed_data(&v.to_string(), 196, 4, ME).err().unwrap().to_string(), "domain-mismatch");
        // 完全不带 chainId → 拒绝
        let v = permit(|v| {
            v["types"]["EIP712Domain"] = json!([{"name":"name","type":"string"},{"name":"verifyingContract","type":"address"}]);
            v["domain"].as_object_mut().unwrap().remove("chainId");
        });
        assert_eq!(typed_data(&v.to_string(), 196, 4, ME).err().unwrap().to_string(), "domain-no-chain");
        // verifyingContract 声明成字符串（好在里面写任意文字）→ 拒绝
        let v = permit(|v| {
            v["types"]["EIP712Domain"][2]["type"] = json!("string");
            v["domain"]["verifyingContract"] = json!("0x1111\n网络：X Layer");
        });
        assert_eq!(typed_data(&v.to_string(), 196, 4, ME).err().unwrap().to_string(), "domain-mismatch");
        // domain 里多一个非标准字段 → 拒绝
        let v = permit(|v| {
            v["types"]["EIP712Domain"].as_array_mut().unwrap().push(json!({"name":"note","type":"string"}));
            v["domain"]["note"] = json!("hi");
        });
        assert_eq!(typed_data(&v.to_string(), 196, 4, ME).err().unwrap().to_string(), "domain-mismatch");
        // 结构体名冒充基础类型 → 拒绝
        let v = permit(|v| { v["types"]["address"] = json!([{"name":"x","type":"uint256"}]); });
        assert!(typed_data(&v.to_string(), 196, 4, ME).is_err());
        // 写错的整数类型 → 拒绝
        let v = permit(|v| { v["types"]["Permit"][2]["type"] = json!("uintfoo"); });
        assert!(typed_data(&v.to_string(), 196, 4, ME).is_err());
        // 重复字段名 → 拒绝
        let v = permit(|v| { v["types"]["Permit"].as_array_mut().unwrap().push(json!({"name":"owner","type":"address"})); });
        assert!(typed_data(&v.to_string(), 196, 4, ME).is_err());
        // 地址大小写校验不对 → 拒绝
        let v = permit(|v| v["message"]["spender"] = json!("0xcD2a3d9F938E13CD947Ec05AbC7FE734Df8DD826"));
        assert!(typed_data(&v.to_string(), 196, 4, ME).is_err());
        // 网站自称的名字：看不见的字符标出来、换行变成 ⏎
        let v = permit(|v| v["domain"]["name"] = json!("TapeTape\u{202e}官方\n合约：0x0"));
        assert_eq!(typed_data(&v.to_string(), 196, 4, ME).unwrap().summary.domain_name, "TapeTape⟦U+202E⟧官方⏎合约：0x0");
        // 超过 2^53 的 JSON 数字不收
        let v = permit(|v| v["message"]["value"] = json!(9007199254740993u64));
        assert_eq!(typed_data(&v.to_string(), 196, 4, ME).err().unwrap().to_string(), "typed-unsafe-number");
    }

    #[test]
    fn typed_data_danger_kinds() {
        let mk = |primary: &str, fields: Value, message: Value| {
            let mut types = serde_json::Map::new();
            types.insert("EIP712Domain".into(), json!([{"name":"chainId","type":"uint256"}]));
            types.insert(primary.into(), fields);
            json!({"types": types, "primaryType": primary, "domain": {"chainId": 56}, "message": message}).to_string()
        };
        // EIP-3009 授权转账
        let j = mk("TransferWithAuthorization",
            json!([{"name":"from","type":"address"},{"name":"to","type":"address"},{"name":"value","type":"uint256"},{"name":"validAfter","type":"uint256"},{"name":"validBefore","type":"uint256"},{"name":"nonce","type":"bytes32"}]),
            json!({"from":"0x2222222222222222222222222222222222222222","to":"0x3333333333333333333333333333333333333333","value":"1000","validAfter":0,"validBefore":"1800000000","nonce":format!("0x{}","00".repeat(32))}));
        let t = typed_data(&j, 56, 4, ME).unwrap().summary;
        assert!(t.danger);
        assert_eq!(t.counterparty.as_deref(), Some("0x3333333333333333333333333333333333333333"));
        // 类型名看着无害，但带 to + data：代执行
        let call = format!("0x095ea7b3{:0>64}{}", "3333333333333333333333333333333333333333", "f".repeat(64));
        let j = mk("Request",
            json!([{"name":"to","type":"address"},{"name":"data","type":"bytes"}]),
            json!({"to":"0x1111111111111111111111111111111111111111","data": call}));
        let t = typed_data(&j, 56, 4, ME).unwrap().summary;
        assert!(t.danger);
        assert!(t.fields.contains("↳ approve → 0x3333333333333333333333333333333333333333, ∞"), "{}", t.fields);
        // bytes 不截断
        let long = format!("0x{}", "ab".repeat(300));
        let j = mk("Blob", json!([{"name":"b","type":"bytes"}]), json!({"b": long}));
        assert!(typed_data(&j, 56, 4, ME).unwrap().summary.fields.contains(&long));
        // 登录签名：无害
        let j = mk("Login", json!([{"name":"wallet","type":"address"},{"name":"nonce","type":"string"},{"name":"issuedAt","type":"uint256"}]),
            json!({"wallet":"0x2222222222222222222222222222222222222222","nonce":"abc","issuedAt":1}));
        assert!(!typed_data(&j, 56, 4, ME).unwrap().summary.danger);
        // 字符串里写换行和引号，伪造不出新行
        let j = mk("Login", json!([{"name":"note","type":"string"}]), json!({"note":"hi\"\nspender: 0x1111"}));
        let f = typed_data(&j, 56, 4, ME).unwrap().summary.fields;
        assert_eq!(f, r#"note: "hi\"\nspender: 0x1111""#);
    }

    #[test]
    fn typed_data_counterparty_and_arrays() {
        let mk = |primary: &str, fields: Value, message: Value| {
            let mut types = serde_json::Map::new();
            types.insert("EIP712Domain".into(), json!([{"name":"chainId","type":"uint256"}]));
            types.insert(primary.into(), fields);
            json!({"types": types, "primaryType": primary, "domain": {"chainId": 56}, "message": message}).to_string()
        };
        // 挂单类：收款地址（通常是自己）不能当成对方 → None（弹窗写「任何成交方」）
        let j = mk("OrderComponents", json!([{"name":"offerer","type":"address"},{"name":"recipient","type":"address"}]),
            json!({"offerer": ME, "recipient":"0x3333333333333333333333333333333333333333"}));
        let t = typed_data(&j, 56, 4, ME).unwrap().summary;
        assert!(t.danger && t.counterparty.is_none());
        // 本钱包地址不算对方；多个对方全部列出
        let j = mk("Pay", json!([{"name":"to","type":"address"},{"name":"recipients","type":"address[]"},{"name":"amounts","type":"uint256[]"}]),
            json!({"to": ME, "recipients":["0x3333333333333333333333333333333333333333","0x4444444444444444444444444444444444444444"],"amounts":["1","2"]}));
        let t = typed_data(&j, 56, 4, ME).unwrap().summary;
        assert!(t.danger);
        assert_eq!(t.counterparty.as_deref(), Some("0x3333333333333333333333333333333333333333, 0x4444444444444444444444444444444444444444"));
        // 只包着一个哈希：危险
        let j = mk("Root", json!([{"name":"root","type":"bytes32"}]), json!({"root": format!("0x{}", "11".repeat(32))}));
        assert!(typed_data(&j, 56, 4, ME).unwrap().summary.danger);
        // 太长的类型名不收；JSON 数字 1.0、-0 按整数收
        let j = mk(&format!("T{}", "x".repeat(100)), json!([{"name":"a","type":"uint256"}]), json!({"a":1}));
        assert!(typed_data(&j, 56, 4, ME).is_err());
        let j = r#"{"types":{"EIP712Domain":[{"name":"chainId","type":"uint256"}],"T":[{"name":"a","type":"uint256"},{"name":"b","type":"int256"}]},"primaryType":"T","domain":{"chainId":56},"message":{"a":1.0,"b":-0}}"#;
        assert!(typed_data(j, 56, 4, ME).is_ok());
    }

    #[test]
    fn typed_data_limits() {
        // 递归类型不收
        let j = json!({"types":{"EIP712Domain":[{"name":"chainId","type":"uint256"}],"Node":[{"name":"kids","type":"Node[]"}]},"primaryType":"Node","domain":{"chainId":56},"message":{"kids":[]}});
        assert_eq!(typed_data(&j.to_string(), 56, 4, ME).err().unwrap().to_string(), "typed-recursive");
        // 一长串首尾相连的类型：类型太多直接拒绝，不会耗尽栈
        let mut types = serde_json::Map::new();
        types.insert("EIP712Domain".into(), json!([{"name":"chainId","type":"uint256"}]));
        for i in 0..5000 {
            types.insert(format!("T{i}"), json!([{"name":"x","type": if i == 4999 { "uint256".to_string() } else { format!("T{}", i + 1) }}]));
        }
        let j = json!({"types": types, "primaryType":"T0","domain":{"chainId":56},"message":{}});
        assert!(typed_data(&j.to_string(), 56, 4, ME).is_err());
    }

    #[test]
    fn bool_and_bytes_strict() {
        let mk = |b: Value| json!({
            "types": {"EIP712Domain":[{"name":"chainId","type":"uint256"}],"T":[{"name":"b","type":"bool"},{"name":"x","type":"bytes"}]},
            "primaryType":"T","domain":{"chainId":56},"message":{"b":b,"x":"0x0102"}
        });
        assert!(typed_data(&mk(json!(true)).to_string(), 56, 4, ME).is_ok());
        assert!(typed_data(&mk(json!("false")).to_string(), 56, 4, ME).is_err(), "字符串 false 不收");
        let mut odd = mk(json!(true));
        odd["message"]["x"] = json!("0x123");
        assert!(typed_data(&odd.to_string(), 56, 4, ME).is_err(), "奇数长度的十六进制不收");
    }

    #[test]
    fn numbers_and_ranges() {
        assert!(word_of_number(&json!("256"), false, 8).is_err(), "uint8 overflow");
        assert!(word_of_number(&json!("255"), false, 8).is_ok());
        assert!(word_of_number(&json!("-1"), false, 256).is_err(), "negative unsigned");
        assert_eq!(word_of_number(&json!("-0"), false, 256).unwrap(), [0u8; 32], "-0 就是 0");
        assert_eq!(word_of_number(&json!("0X10"), false, 256).unwrap()[31], 16);
        assert!(word_of_number(&json!("-128"), true, 8).is_ok(), "int8 min");
        assert!(word_of_number(&json!("-129"), true, 8).is_err());
        assert!(word_of_number(&json!("128"), true, 8).is_err());
        assert_eq!(word_of_number(&json!("-1"), true, 256).unwrap(), [0xff; 32]);
        assert!(word_of_number(&json!(1.5), false, 256).is_err());
        let mut w = [0u8; 32];
        w[31] = 0x40;
        w[30] = 0x42;
        w[29] = 0x0f;
        assert_eq!(word_to_decimal(&w), "1000000");
        assert_eq!(format_units(&w, 6), "1");
        assert_eq!(format_units(&w, 8), "0.01");
        assert_eq!(format_units(&[0u8; 32], 18), "0");
        assert!(is_unlimited(&[0xff; 32]));
        assert_eq!(utc_time(0), "1970-01-01 00:00 UTC");
        assert_eq!(utc_time(1_700_000_000), "2023-11-14 22:13 UTC");
    }

    #[test]
    fn parse_transactions() {
        let me = "0x2c7536e3605d9c16a7a3d7b1898e529396a65c23";
        let p = parse_tx(r#"{"from":"0x2C7536E3605D9C16a7a3D7b1898e529396a65c23","to":"0x1111111111111111111111111111111111111111","value":"0xde0b6b3a7640000","data":"0x12345678","gas":"0x5208"}"#, me, 196).unwrap();
        assert_eq!(p.call.value, 1_000_000_000_000_000_000);
        assert_eq!(p.call.data, vec![0x12, 0x34, 0x56, 0x78]);
        assert_eq!(p.gas_hint, Some(21000));
        assert!(parse_tx(r#"{"to":"0x1111111111111111111111111111111111111111","from":"0x3333333333333333333333333333333333333333"}"#, me, 196).is_err(), "someone else's from");
        assert_eq!(parse_tx(r#"{"data":"0x6000"}"#, me, 196).err().unwrap().to_string(), "no-contract-creation");
        // data 为 null 时用 input
        let p = parse_tx(r#"{"to":"0x1111111111111111111111111111111111111111","data":null,"input":"0xabcdef01"}"#, me, 196).unwrap();
        assert_eq!(p.call.data.len(), 4);
        // 十进制字符串的金额不收（"10" 到底是 10 wei 还是 10 个币？）
        assert!(parse_tx(r#"{"to":"0x1111111111111111111111111111111111111111","value":"10"}"#, me, 196).is_err());
        // 交易里写的链号必须是当前链
        assert_eq!(parse_tx(r#"{"to":"0x1111111111111111111111111111111111111111","chainId":"0x38"}"#, me, 196).err().unwrap().to_string(), "chain-mismatch");
        assert!(parse_tx(r#"{"to":"0x1111111111111111111111111111111111111111","chainId":"0xc4"}"#, me, 196).is_ok());
    }

    #[test]
    fn decode_common_calls() {
        let to = [0x11u8; 20];
        let spender = "3333333333333333333333333333333333333333";
        let max = "f".repeat(64);
        let data = hex::decode(format!("095ea7b3{:0>64}{max}", spender)).unwrap();
        let d = decode_call(&data, to).unwrap();
        assert_eq!(d.action, "approve");
        assert!(d.danger && !d.extra && is_unlimited(&d.amount.unwrap()));
        assert_eq!(hex0x(&d.counterparty), format!("0x{spender}"));
        // 后面多塞一段：照样认出 approve，并标出多余数据
        let data = hex::decode(format!("095ea7b3{:0>64}{max}{}", spender, "0".repeat(64))).unwrap();
        let d = decode_call(&data, to).unwrap();
        assert!(d.extra && d.danger);
        let data = hex::decode(format!("a9059cbb{:0>64}{:0>64}", spender, "3e8")).unwrap();
        let d = decode_call(&data, to).unwrap();
        assert_eq!((d.action, d.danger), ("transfer", false));
        let data = hex::decode(format!("a22cb465{:0>64}{:0>64}", spender, "1")).unwrap();
        assert_eq!(decode_call(&data, to).unwrap().action, "approval-for-all");
        let data = hex::decode(format!("a22cb465{:0>64}{:0>64}", spender, "0")).unwrap();
        let d = decode_call(&data, to).unwrap();
        assert_eq!((d.action, d.danger), ("revoke-all", false));
        // 布尔值写成 0x…0100（老编译器当 true）：不认 → 按无法识别（危险）处理
        let data = hex::decode(format!("a22cb465{:0>64}{:0>64}", spender, "100")).unwrap();
        assert!(decode_call(&data, to).is_none());
        // 参数不够、地址高位不为 0 → 不认
        assert!(decode_call(&hex::decode(format!("095ea7b3{:0>64}", spender)).unwrap(), to).is_none());
        assert!(decode_call(&hex::decode(format!("095ea7b3{}{max}", "f".repeat(64))).unwrap(), to).is_none());
    }

    #[test]
    fn text_cleaning() {
        // 行分隔符、韩文填充符、盲文空白都标出来
        assert_eq!(oneline("a\u{2028}b\u{3164}c\u{2800}d\ne", 100), "a⟦U+2028⟧b⟦U+3164⟧c⟦U+2800⟧d⏎e");
        // 换行之间夹空格也算空行，压成一行
        let (t, _) = multiline("a\n \n \n \n \nb", 60);
        assert_eq!(t, "a\n\nb");
        let (t, cut) = multiline(&(0..100).map(|i| i.to_string()).collect::<Vec<_>>().join("\n"), 60);
        assert!(cut);
        assert!(t.ends_with("⋯ (+40)"));
        // 只含不换行空白 / 全角空格的行：标出来或压成一个，不再原样保留几千个
        let (t, _) = multiline(&format!("登录\n{}\n转让", "\u{3000}".repeat(3000)), 60);
        assert_eq!(t, "登录\n\n转让");
        let (t, _) = multiline(&format!("Sign in{}I approve", " ".repeat(5000)), 60);
        assert_eq!(t, "Sign in ␣×5000 I approve");
        assert!(oneline(&format!("a{}b", "\u{a0}".repeat(3)), 100).contains("⟦U+00A0⟧"));
        // 私用区字符、叠加太多的组合符号标出来
        assert_eq!(oneline("\u{f8ff}", 10), "⟦U+F8FF⟧");
        assert!(oneline("a\u{301}\u{301}\u{301}\u{301}", 50).contains("⟦U+0301⟧"));
        // emoji 的变体选择符、零宽连接符不标
        assert_eq!(oneline("❤️ 👨\u{200d}👩", 50), "❤️ 👨\u{200d}👩");
    }

    #[test]
    fn messages() {
        let m = message_of("hello\u{202e}dlrow\n\n\n\n\nend");
        assert!(!m.blind);
        assert_eq!(m.text, "hello⟦U+202E⟧dlrow\n\nend");
        assert_eq!(m.hex, hex0x("hello\u{202e}dlrow\n\n\n\n\nend".as_bytes()));
        // 32 字节十六进制 = 盲签
        assert!(message_of(&format!("0x{}", "ab".repeat(32))).blind);
        // 奇数长度、带符号都不算十六进制，按文字签
        assert!(!message_of("0x123").blind);
        assert_eq!(message_of("0x123").hex, hex0x(b"0x123"));
        assert_eq!(message_of("0x+a+b").hex, hex0x(b"0x+a+b"));
        // 中文、emoji 登录消息不算盲签
        assert!(!message_of("欢迎登录 TapeCode！\n地址：0x2c75…\n随机数：8f3a").blind);
        assert!(!message_of("❤️❤️❤️❤️ 欢迎").blind);
        assert!(!message_of("🇨🇳🇨🇳🇨🇳🇨🇳 登录").blind);
        // 读不成文字的字节 = 盲签
        assert!(message_of("0xfffe").blind);
        // 合法 UTF-8 但几乎都是怪字符 = 盲签
        assert!(message_of(&"\u{0300}\u{0301}\u{0302}\u{0303}\u{0304}\u{0305}\u{0306}\u{0307}\u{0308}".repeat(3)).blind);
        // 行数太多 = 盲签
        assert!(message_of(&"x\n".repeat(80)).blind);
    }

    #[test]
    fn get_logs_rate_and_ticket() {
        assert!(check_get_logs(&json!([{"address":"0x11","fromBlock":"0x10","toBlock":"0x20"}])).is_ok());
        assert!(check_get_logs(&json!([{"fromBlock":"0x10","toBlock":"0x20"}])).is_err(), "必须带地址");
        assert!(check_get_logs(&json!([{"address":"0x11","fromBlock":"0x0","toBlock":"0x100000"}])).is_err(), "范围太大");
        assert!(check_get_logs(&json!([{"address":"0x11","fromBlock":"latest"}])).is_err());
        assert!(check_get_logs(&json!([{"address":"0x11","blockHash":"0xab"}])).is_ok());
        let origin = "https://rate.test";
        let free = (0..30).filter(|_| rate_wait(origin).is_zero()).count();
        assert_eq!(free, 20, "一下子最多 20 次不用等，之后要排队");
        assert!(rate_wait("https://other.test").is_zero(), "各网站分开算");
        // 票据只能用一次，内容对不上不收
        let t = issue_ticket(196, "{}", "0.1");
        assert!(!take_ticket(&t, 56, "{}", "0.1"), "链号对不上（同时作废）");
        let t = issue_ticket(196, "{}", "0.1");
        assert!(take_ticket(&t, 196, "{}", "0.1"));
        assert!(!take_ticket(&t, 196, "{}", "0.1"), "第二次不收");
    }
}
