use serde::Serialize;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct UploadRequest {
    pub account: String,
    pub server: String,
    pub object: [u8; 32],
    pub timestamp: u64,
    pub nonce: [u8; 16],
}

#[derive(Serialize)]
struct MessageJson<'a> {
    account: &'a str,
    server: &'a str,
    method: &'a str,
    path: &'a str,
    query: &'a str,
    #[serde(rename = "bodyHash")]
    body_hash: String,
    timestamp: u64,
    nonce: String,
}

#[derive(Serialize)]
struct ItemJson<'a> {
    message: MessageJson<'a>,
}

#[derive(Serialize)]
struct BatchJson<'a> {
    requests: Vec<ItemJson<'a>>,
}

impl UploadRequest {
    pub fn headers(&self, signature: &str, delegation: Option<&str>) -> Vec<(String, String)> {
        let mut h = vec![
            ("TR-Account".to_string(), self.account.clone()),
            ("TR-Timestamp".to_string(), self.timestamp.to_string()),
            ("TR-Nonce".to_string(), hex::encode(self.nonce)),
            ("TR-Signature".to_string(), signature.to_string()),
        ];
        if let Some(d) = delegation {
            h.push(("TR-Delegation".to_string(), d.to_string()));
        }
        h.push(("TR-Object".to_string(), hex::encode(self.object)));
        h
    }

    fn message(&self) -> MessageJson<'_> {
        MessageJson {
            account: &self.account,
            server: &self.server,
            method: "POST",
            path: "/v1/objects",
            query: "",
            body_hash: format!("0x{}", hex::encode(self.object)),
            timestamp: self.timestamp,
            nonce: format!("0x{}", hex::encode(self.nonce)),
        }
    }
}

pub fn remote_batch_json(requests: &[UploadRequest]) -> String {
    serde_json::to_string(&BatchJson {
        requests: requests
            .iter()
            .map(|r| ItemJson {
                message: r.message(),
            })
            .collect(),
    })
    .expect("json of plain strings")
}
