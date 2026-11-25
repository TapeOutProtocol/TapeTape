#!/usr/bin/env bash
# 重新编译合约，生成部署页面用的 artifact.js（字节码、接口、源码验证材料）。
set -euo pipefail
cd "$(dirname "$0")/.."
forge build --force >/dev/null
forge verify-contract --show-standard-json-input 0x0000000000000000000000000000000000000000 \
  src/TapeTapeRedPacket.sol:TapeTapeRedPacket > /tmp/tapetape-stdjson.$$
forge inspect TapeTapeRedPacket methodIdentifiers --json > /tmp/tapetape-sel.$$
forge inspect TapeTapeRedPacket errors --json > /tmp/tapetape-err.$$
node - out/TapeTapeRedPacket.sol/TapeTapeRedPacket.json /tmp/tapetape-stdjson.$$ /tmp/tapetape-sel.$$ /tmp/tapetape-err.$$ deploy/artifact.js <<'JS'
const fs = require('fs');
const crypto = require('crypto');
const [art, std, sel, err, dest] = process.argv.slice(2);
const a = JSON.parse(fs.readFileSync(art, 'utf8'));
const meta = a.rawMetadata ? JSON.parse(a.rawMetadata) : a.metadata;
const out = {
  contractName: 'TapeTapeRedPacket',
  contractIdentifier: 'src/TapeTapeRedPacket.sol:TapeTapeRedPacket',
  compilerVersion: meta.compiler.version,
  evmVersion: meta.settings.evmVersion,
  bytecode: a.bytecode.object,
  deployedBytecode: a.deployedBytecode.object,
  bytecodeSha256: crypto.createHash('sha256').update(Buffer.from(a.bytecode.object.slice(2), 'hex')).digest('hex'),
  sourceSha256: crypto.createHash('sha256').update(fs.readFileSync('src/TapeTapeRedPacket.sol')).digest('hex'),
  selectors: JSON.parse(fs.readFileSync(sel, 'utf8')),
  errors: JSON.parse(fs.readFileSync(err, 'utf8')),
  stdJsonInput: JSON.parse(fs.readFileSync(std, 'utf8')),
  builtAt: new Date().toISOString(),
};
fs.writeFileSync(dest, '// 由 build-artifact.sh 生成，不要手改。\nwindow.TAPETAPE_ARTIFACT = ' + JSON.stringify(out) + ';\n');
console.log('compiler', out.compilerVersion, 'evm', out.evmVersion, 'bytecode sha256', out.bytecodeSha256);
JS
# 客户端模拟测试把这份运行时代码注入到测试地址上。
node -e 'const a=require("./out/TapeTapeRedPacket.sol/TapeTapeRedPacket.json");process.stdout.write(a.deployedBytecode.object)' \
  > ../core/crates/tapetape-core/src/testdata/redpacket_runtime.hex
rm -f /tmp/tapetape-stdjson.$$ /tmp/tapetape-sel.$$ /tmp/tapetape-err.$$
