# TapeTape 大附件签名后台

App 发超过约 170 KB 的图片、视频、文件时，要先把加密后的文件分块传到 TapeRelay 中继网关的通信节点。每次上传都要电路的签名，这个后台替 App 签：

- `GET /api/relay/sign` → `{ account, servers }`：电路名和存文件用的通信节点。
- `POST /api/relay/sign`：App 用自己的身份签名请求（请求头 `TR-Account`、`TR-Timestamp`、`TR-Nonce`、`TR-Signature`），后台核对身份、拒绝重放、按天限额（默认每个账号每天 1 GiB，一个对象按 1 MiB 计），再用开发者密钥签上传和认领请求。只签发往电路自己通信节点的上传、认领请求。

开发者密钥只从环境变量 `TAPERELAY_KEY` 读，不写日志，不回给调用方。

## 运行

- Node 22.13 及以上（用到 `node:sqlite`）。
- `npm ci && npm test`
- 环境变量：`TAPERELAY_KEY`（必填）、`RELAY_DAILY_QUOTA_BYTES`（选填）、`HOST`、`PORT`（默认 127.0.0.1:3010）、`DB_PATH`（限额和防重放记录，SQLite）。

## 部署（tapetape.org 那台服务器）

- 程序 `/opt/tapetape-files/app/`，Node `/opt/tapetape-files/runtime/node`，服务 `deploy/tapetape-files.service`，数据 `/var/lib/tapetape-files/`。
- nginx：把 `deploy/nginx-location.conf` 加进 tapetape.org 的 443 server 段。
- 写密钥：`sudo /opt/tapetape-files/set-key`（`deploy/set-key`），可带一个参数改每日限额（字节）。
- App 里的后台地址填 `https://tapetape.org`（iOS `TAPETAPE_FILES_BACKEND`，Android `tapetape.filesBackend`）。
