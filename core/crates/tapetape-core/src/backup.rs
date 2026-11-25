use std::fs::File;
use std::io::{self, BufReader, BufWriter, Read, Write};
use std::path::{Path, PathBuf};

use anyhow::{bail, ensure, Context, Result};
use chacha20poly1305::aead::stream::{DecryptorBE32, EncryptorBE32};
use chacha20poly1305::aead::{KeyInit, Payload};
use chacha20poly1305::XChaCha20Poly1305;
use sha2::{Digest, Sha256};

use crate::account::keys;

const MAGIC: &[u8; 8] = b"TAPETAP1";
const CHUNK: usize = 64 << 10;
const TAG: usize = 16;
const ADDRESS_LEN: usize = 42;
const HEADER_LEN: usize = 8 + 1 + 16 + 19 + ADDRESS_LEN;
pub(crate) const MODE_PASSPHRASE: u8 = 1;
pub(crate) const MODE_RECOVERY: u8 = 2;

pub(crate) struct Header {
    pub mode: u8,
    salt: [u8; 16],
    nonce: [u8; 19],
    pub address: String,
    raw: Vec<u8>,
}

impl Header {
    fn new(mode: u8, address: &str) -> Result<Header> {
        ensure!(address.len() == ADDRESS_LEN, "unexpected address {address}");
        let salt: [u8; 16] = rand::random();
        let nonce: [u8; 19] = rand::random();
        let mut raw = Vec::with_capacity(HEADER_LEN);
        raw.extend_from_slice(MAGIC);
        raw.push(mode);
        raw.extend_from_slice(&salt);
        raw.extend_from_slice(&nonce);
        raw.extend_from_slice(address.as_bytes());
        Ok(Header {
            mode,
            salt,
            nonce,
            address: address.to_string(),
            raw,
        })
    }

    pub(crate) fn read(path: &Path) -> Result<(Header, BufReader<File>)> {
        let mut f = BufReader::new(File::open(path).context("Cannot open the backup file")?);
        let mut raw = vec![0u8; HEADER_LEN];
        f.read_exact(&mut raw)
            .ok()
            .filter(|_| raw.starts_with(MAGIC))
            .context("This is not a TapeTape backup file")?;
        let mode = raw[8];
        ensure!(
            mode == MODE_PASSPHRASE || mode == MODE_RECOVERY,
            "This backup was made by a newer version of TapeTape"
        );
        Ok((
            Header {
                mode,
                salt: raw[9..25].try_into()?,
                nonce: raw[25..44].try_into()?,
                address: String::from_utf8(raw[44..].to_vec())?,
                raw,
            },
            f,
        ))
    }

    pub(crate) fn key(&self, secret: &str) -> Result<[u8; 32]> {
        if self.mode == MODE_PASSPHRASE {
            return passphrase_key(secret, &self.salt);
        }
        let sk = keys::secret_from_mnemonic(secret).context("Invalid recovery phrase")?;
        ensure!(
            taperelay::address_of(&sk)?.eq_ignore_ascii_case(&self.address),
            "This recovery phrase does not belong to this backup"
        );
        Ok(account_key(&sk))
    }
}

fn passphrase_key(passphrase: &str, salt: &[u8]) -> Result<[u8; 32]> {
    let params = scrypt::Params::new(15, 8, 1, 32).map_err(|e| anyhow::anyhow!("{e}"))?;
    let mut key = [0u8; 32];
    scrypt::scrypt(passphrase.as_bytes(), salt, &params, &mut key)
        .map_err(|e| anyhow::anyhow!("{e}"))?;
    Ok(key)
}

fn account_key(secret: &[u8; 32]) -> [u8; 32] {
    Sha256::new()
        .chain_update(b"tapetape/backup/v1")
        .chain_update(secret)
        .finalize()
        .into()
}

struct SealWriter<W: Write> {
    out: W,
    enc: Option<EncryptorBE32<XChaCha20Poly1305>>,
    buf: Vec<u8>,
    aad: Vec<u8>,
}

impl<W: Write> SealWriter<W> {
    fn finish(mut self) -> Result<W> {
        let enc = self.enc.take().context("already finished")?;
        let ct = enc
            .encrypt_last(Payload {
                msg: &self.buf,
                aad: &self.aad,
            })
            .map_err(|_| anyhow::anyhow!("encryption failed"))?;
        self.out.write_all(&ct)?;
        self.out.flush()?;
        Ok(self.out)
    }
}

impl<W: Write> Write for SealWriter<W> {
    fn write(&mut self, data: &[u8]) -> io::Result<usize> {
        self.buf.extend_from_slice(data);
        while self.buf.len() > CHUNK {
            let chunk: Vec<u8> = self.buf.drain(..CHUNK).collect();
            let ct = self
                .enc
                .as_mut()
                .ok_or_else(|| io::Error::other("already finished"))?
                .encrypt_next(Payload {
                    msg: &chunk,
                    aad: &self.aad,
                })
                .map_err(|_| io::Error::other("encryption failed"))?;
            self.out.write_all(&ct)?;
        }
        Ok(data.len())
    }

    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}

struct OpenReader<R: Read> {
    inp: R,
    dec: Option<DecryptorBE32<XChaCha20Poly1305>>,
    aad: Vec<u8>,
    ahead: Vec<u8>,
    plain: Vec<u8>,
    pos: usize,
}

impl<R: Read> OpenReader<R> {
    fn fill(&mut self) -> io::Result<()> {
        let want = CHUNK + TAG + 1;
        while self.ahead.len() < want {
            let start = self.ahead.len();
            self.ahead.resize(want, 0);
            let n = self.inp.read(&mut self.ahead[start..])?;
            self.ahead.truncate(start + n);
            if n == 0 {
                break;
            }
        }
        let bad = |_| io::Error::other("Wrong passphrase or damaged backup");
        self.plain = if self.ahead.len() == want {
            let block: Vec<u8> = self.ahead.drain(..CHUNK + TAG).collect();
            self.dec
                .as_mut()
                .ok_or_else(|| io::Error::other("read past end"))?
                .decrypt_next(Payload {
                    msg: &block,
                    aad: &self.aad,
                })
                .map_err(bad)?
        } else {
            let last = std::mem::take(&mut self.ahead);
            self.dec
                .take()
                .ok_or_else(|| io::Error::other("read past end"))?
                .decrypt_last(Payload {
                    msg: &last,
                    aad: &self.aad,
                })
                .map_err(bad)?
        };
        self.pos = 0;
        Ok(())
    }
}

impl<R: Read> Read for OpenReader<R> {
    fn read(&mut self, out: &mut [u8]) -> io::Result<usize> {
        while self.pos == self.plain.len() {
            if self.dec.is_none() {
                return Ok(0);
            }
            self.fill()?;
        }
        let n = out.len().min(self.plain.len() - self.pos);
        out[..n].copy_from_slice(&self.plain[self.pos..self.pos + n]);
        self.pos += n;
        Ok(n)
    }
}

pub(crate) fn write(
    dest: &Path,
    address: &str,
    passphrase: Option<&str>,
    mnemonic: &str,
    db_snapshot: &Path,
    blobs: &Path,
) -> Result<()> {
    let (header, key) = match passphrase {
        Some(p) => {
            let h = Header::new(MODE_PASSPHRASE, address)?;
            let key = passphrase_key(p, &h.salt)?;
            (h, key)
        }
        None => (
            Header::new(MODE_RECOVERY, address)?,
            account_key(&keys::secret_from_mnemonic(mnemonic)?),
        ),
    };
    let mut out = BufWriter::new(File::create(dest).context("Cannot write the backup file")?);
    out.write_all(&header.raw)?;
    let cipher = XChaCha20Poly1305::new(&key.into());
    let seal = SealWriter {
        out,
        enc: Some(EncryptorBE32::from_aead(
            cipher,
            header.nonce.as_ref().into(),
        )),
        buf: Vec::with_capacity(CHUNK * 2),
        aad: header.raw.clone(),
    };
    let mut tar = tar::Builder::new(seal);
    tar.append_path_with_name(db_snapshot, "db.sqlite")?;
    tar.append_dir_all("blobs", blobs)?;
    tar.into_inner()?.finish()?;
    Ok(())
}

pub(crate) fn unpack(
    header: &Header,
    inp: BufReader<File>,
    key: [u8; 32],
    dest: &Path,
) -> Result<()> {
    let cipher = XChaCha20Poly1305::new(&key.into());
    let mut reader = OpenReader {
        inp,
        dec: Some(DecryptorBE32::from_aead(
            cipher,
            header.nonce.as_ref().into(),
        )),
        aad: header.raw.clone(),
        ahead: Vec::new(),
        plain: Vec::new(),
        pos: 0,
    };
    reader
        .fill()
        .map_err(|_| anyhow::anyhow!("Wrong passphrase or damaged backup"))?;
    let mut archive = tar::Archive::new(reader);
    for entry in archive.entries()? {
        let mut entry = entry?;
        let path: PathBuf = entry.path()?.into_owned();
        let ok = path == Path::new("db.sqlite")
            || (path.starts_with("blobs")
                && path
                    .components()
                    .all(|c| matches!(c, std::path::Component::Normal(_))));
        let kind = entry.header().entry_type();
        if !ok || !(kind.is_file() || kind.is_dir()) {
            bail!("unexpected entry {} in backup", path.display());
        }
        entry.unpack_in(dest)?;
    }
    ensure!(
        dest.join("db.sqlite").exists(),
        "The backup contains no account"
    );
    Ok(())
}

pub(crate) fn local_date(ms: i64) -> String {
    date(ms + utc_offset_secs(ms / 1000) * 1000)
}

fn utc_offset_secs(secs: i64) -> i64 {
    let t = secs as libc::time_t;
    let mut tm: libc::tm = unsafe { std::mem::zeroed() };
    if unsafe { libc::localtime_r(&t, &mut tm) }.is_null() {
        return 0;
    }
    tm.tm_gmtoff as i64
}

pub(crate) fn date(ms: i64) -> String {
    let days = ms.div_euclid(86_400_000);
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    let y = yoe + era * 400 + i64::from(m <= 2);
    format!("{y:04}-{m:02}-{d:02}")
}

#[cfg(test)]
mod tests {
    use super::*;

    extern "C" {
        fn tzset();
    }

    #[test]
    fn civil_date() {
        assert_eq!(date(0), "1970-01-01");
        assert_eq!(date(1_763_600_000_000), "2025-11-20");
        assert_eq!(date(951_782_400_000), "2000-02-29");
        std::env::set_var("TZ", "Asia/Shanghai");
        unsafe { tzset() };
        assert_eq!(local_date(1_763_683_200_000 - 3_600_000), "2025-11-21");
        assert_eq!(date(1_763_683_200_000 - 3_600_000), "2025-11-20");
        std::env::set_var("TZ", "America/Los_Angeles");
        unsafe { tzset() };
        assert_eq!(local_date(1_763_683_200_000 + 3_600_000), "2025-11-20");
    }

    #[test]
    fn restore_accepts_only_files_and_dirs() {
        let dir = tempfile::tempdir().unwrap();
        let address = format!("0x{}", "a".repeat(40));
        let key = [9u8; 32];
        let sealed = |link: bool| {
            let header = Header::new(MODE_PASSPHRASE, &address).unwrap();
            let path = dir.path().join(format!("{link}.tapetapebak"));
            let mut out = File::create(&path).unwrap();
            out.write_all(&header.raw).unwrap();
            let mut tar = tar::Builder::new(SealWriter {
                out,
                enc: Some(EncryptorBE32::from_aead(
                    XChaCha20Poly1305::new(&key.into()),
                    header.nonce.as_ref().into(),
                )),
                buf: Vec::new(),
                aad: header.raw.clone(),
            });
            let mut h = tar::Header::new_gnu();
            h.set_size(2);
            h.set_mode(0o644);
            tar.append_data(&mut h, "db.sqlite", &b"db"[..]).unwrap();
            let mut h = tar::Header::new_gnu();
            if link {
                h.set_entry_type(tar::EntryType::Symlink);
                h.set_size(0);
                tar.append_link(&mut h, "blobs/x", "/etc/passwd").unwrap();
            } else {
                h.set_size(1);
                h.set_mode(0o644);
                tar.append_data(&mut h, "blobs/x", &b"x"[..]).unwrap();
            }
            tar.into_inner().unwrap().finish().unwrap();
            path
        };
        for link in [false, true] {
            let (header, file) = Header::read(&sealed(link)).unwrap();
            let dest = dir.path().join(format!("out-{link}"));
            std::fs::create_dir_all(dest.join("blobs")).unwrap();
            let r = unpack(&header, file, key, &dest);
            assert_eq!(r.is_ok(), !link, "{r:?}");
            assert_eq!(dest.join("blobs/x").exists(), !link);
        }
    }

    #[test]
    fn stream_roundtrip_on_chunk_boundaries() {
        for len in [0, 1, CHUNK - 1, CHUNK, CHUNK + 1, 3 * CHUNK] {
            let data: Vec<u8> = (0..len).map(|i| (i * 7) as u8).collect();
            let key = [7u8; 32];
            let nonce = [3u8; 19];
            let mut w = SealWriter {
                out: Vec::new(),
                enc: Some(EncryptorBE32::from_aead(
                    XChaCha20Poly1305::new(&key.into()),
                    nonce.as_ref().into(),
                )),
                buf: Vec::new(),
                aad: b"h".to_vec(),
            };
            w.write_all(&data).unwrap();
            let ct = w.finish().unwrap();
            let mut r = OpenReader {
                inp: ct.as_slice(),
                dec: Some(DecryptorBE32::from_aead(
                    XChaCha20Poly1305::new(&key.into()),
                    nonce.as_ref().into(),
                )),
                aad: b"h".to_vec(),
                ahead: Vec::new(),
                plain: Vec::new(),
                pos: 0,
            };
            let mut back = Vec::new();
            r.read_to_end(&mut back).unwrap();
            assert_eq!(back, data, "len {len}");
        }
    }
}
