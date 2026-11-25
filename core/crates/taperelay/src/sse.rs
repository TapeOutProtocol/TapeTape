use anyhow::{ensure, Result};

const MAX_BLOCK: usize = 65536;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SseEvent {
    pub id: Option<String>,
    pub event: String,
    pub data: String,
}

impl SseEvent {
    pub fn seq(&self) -> Option<u64> {
        let id = self.id.as_deref()?;
        if id.is_empty() || id.len() > 15 || !id.bytes().all(|c| c.is_ascii_digit()) {
            return None;
        }
        id.parse().ok()
    }
}

#[derive(Default)]
pub struct SseParser {
    buf: Vec<u8>,
}

impl SseParser {
    pub fn new() -> SseParser {
        SseParser::default()
    }

    pub fn push(&mut self, chunk: &[u8]) -> Result<Vec<SseEvent>> {
        self.buf.extend_from_slice(chunk);
        let mut events = Vec::new();
        while let Some(i) = self.buf.windows(2).position(|w| w == b"\n\n") {
            let block: Vec<u8> = self.buf.drain(..i + 2).take(i).collect();
            if let Some(ev) = parse_block(&String::from_utf8_lossy(&block)) {
                events.push(ev);
            }
        }
        ensure!(self.buf.len() <= MAX_BLOCK, "sse block over 64 KiB");
        Ok(events)
    }
}

fn parse_block(block: &str) -> Option<SseEvent> {
    let mut ev = SseEvent {
        id: None,
        event: "message".to_string(),
        data: String::new(),
    };
    for line in block.split('\n') {
        if line.starts_with(':') {
            continue;
        }
        let (key, value) = match line.split_once(':') {
            Some((k, v)) => (k, v.strip_prefix(' ').unwrap_or(v)),
            None => (line, ""),
        };
        match key {
            "id" => ev.id = Some(value.to_string()),
            "event" => ev.event = value.to_string(),
            "data" => {
                if !ev.data.is_empty() {
                    ev.data.push('\n');
                }
                ev.data.push_str(value);
            }
            _ => {}
        }
    }
    (!ev.data.is_empty() || ev.id.is_some()).then_some(ev)
}
