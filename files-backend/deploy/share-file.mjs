// 把一个文件加密后传到电路的 TapeRelay 节点，打印任何人都能打开下载的分享链接。
// 在 tapetape.org 服务器上运行（用 /etc/tapetape-files/env 里的电路开发者密钥，密钥不离开服务器）：
//   sudo /opt/tapetape-files/share-file <文件路径> [显示的文件名]
// 链接形如 https://www.taperelay.com/f/<电路>/<信封指纹>#k=<文件钥匙>&s=<节点>&lang=zh；钥匙在 # 后面，浏览器不会把它发给任何服务器。
import { readFileSync } from 'node:fs';
import { basename } from 'node:path';
import { createClient, planEncryptedFile, generateKeyPair, uploadToServers, shareLink, developerKeyAuth, serversOfKey, describeDeveloperKey } from '@taperelay/client';

const [path, shownName] = process.argv.slice(2);
if (!path) { console.error('用法：share-file <文件路径> [显示的文件名]'); process.exit(1); }
const key = process.env.TAPERELAY_KEY;
const { account } = describeDeveloperKey(key);
const servers = serversOfKey(key);
const name = shownName || basename(path);
const bytes = new Uint8Array(readFileSync(path));
const contentType = name.endsWith('.apk') ? 'application/vnd.android.package-archive' : 'application/octet-stream';

// 公开分享：信封里放一份给一把临时公钥（格式要求至少一份），真正打开靠链接里的文件钥匙
const temp = generateKeyPair();
const plan = await planEncryptedFile(bytes, { name, contentType, from: account, selfKey: temp.publicKey, wholeSha256: true });
temp.secretKey.fill(0);
console.log(`文件 ${name}，${(bytes.length / 1048576).toFixed(1)} MB，${plan.chunkHashes.length} 块，电路 ${account}，节点 ${servers.join(', ')}`);

let last = 0;
const r = await uploadToServers(createClient(), servers, developerKeyAuth(key), bytes, {
  plan,
  onProgress: ({ done, total }) => { const p = Math.floor((done / total) * 10); if (p > last) { last = p; console.log(`上传 ${p * 10}%`); } },
});
if (r.message) { console.error('没传够：' + r.message); process.exit(2); }
const final = await r.settled;
console.log(`已存好：${final.ok.join(', ')}${final.failed.length ? `；失败：${final.failed.map((f) => f.server).join(', ')}` : ''}`);

const link = shareLink({ base: 'https://www.taperelay.com', circuit: account, envelope: plan.envelope.sha256, fileKey: plan.fileKey, servers }) + '&lang=zh';
plan.dispose();
console.log(`SHA-256 ${plan.manifest.sha256 ?? ''}`);
console.log(`分享链接：${link}`);
