#!/bin/bash
# 在本机（Mac）运行：把签名后台装到 tapetape.org 那台服务器，接到 https://tapetape.org/api/relay/sign。
# 不写密钥；装完后另外运行 set-key 写入。重复运行是安全的（只更新程序，不动密钥和限额记录）。
set -euo pipefail
SERVER="${SERVER:-ubuntu@tapetape.org}"
cd "$(dirname "$0")/.."
[ -d node_modules ] || npm ci --no-audit --no-fund
npm test >/dev/null && echo "本机测试通过"
PKG="$(mktemp -t files-backend).tgz"
COPYFILE_DISABLE=1 tar --no-xattrs -czf "$PKG" server.mjs package.json package-lock.json node_modules deploy
scp -q "$PKG" "$SERVER:/tmp/files-backend.tgz"
rm -f "$PKG"
ssh "$SERVER" 'set -e
rm -rf /tmp/tf && mkdir /tmp/tf && tar xzf /tmp/files-backend.tgz -C /tmp/tf
sudo install -d -m 755 /opt/tapetape-files/runtime /opt/tapetape-files/app
[ -x /opt/tapetape-files/runtime/node ] || sudo cp /opt/tapeout-v3/runtime/node-v24.21.0/bin/node /opt/tapetape-files/runtime/node
sudo rm -rf /opt/tapetape-files/app.new && sudo mkdir /opt/tapetape-files/app.new
sudo cp -r /tmp/tf/server.mjs /tmp/tf/package.json /tmp/tf/package-lock.json /tmp/tf/node_modules /opt/tapetape-files/app.new/
sudo rm -rf /opt/tapetape-files/app && sudo mv /opt/tapetape-files/app.new /opt/tapetape-files/app
sudo chown -R root:root /opt/tapetape-files && sudo chmod -R go-w /opt/tapetape-files
sudo install -m 755 /tmp/tf/deploy/set-key /opt/tapetape-files/set-key
sudo install -m 755 /tmp/tf/deploy/set-rpc /opt/tapetape-files/set-rpc
sudo install -m 755 /tmp/tf/deploy/share-file /opt/tapetape-files/share-file
sudo install -m 755 /tmp/tf/deploy/set-apk /opt/tapetape-files/set-apk
sudo install -m 755 /tmp/tf/deploy/publish-apk /opt/tapetape-files/publish-apk
sudo install -m 644 /tmp/tf/deploy/share-file.mjs /opt/tapetape-files/app/share-file.mjs
sudo install -m 644 /tmp/tf/deploy/tapetape-files.service /etc/systemd/system/tapetape-files.service
C=/etc/nginx/sites-available/tapetape-web.conf
# 固定短链接 /apk（set-apk 改写 /etc/nginx/tapetape-apk.conf）
[ -f /etc/nginx/tapetape-apk.conf ] || echo "# 由 set-apk 生成" | sudo tee /etc/nginx/tapetape-apk.conf >/dev/null
if ! grep -q "tapetape-apk.conf" $C; then
  sudo cp $C $C.bak-$(date +%Y%m%d%H%M%S)
  sudo sed -i "s#^    location = /lander#    include /etc/nginx/tapetape-apk.conf;\n&#" $C
fi
# 安卓安装包直接下载 /download/（publish-apk 放文件）
if ! grep -q "tapetape-download" $C; then
  sudo cp $C $C.bak-$(date +%Y%m%d%H%M%S)
  sudo python3 - "$C" /tmp/tf/deploy/download-location.conf <<PY
import sys
c = open(sys.argv[1]).read(); loc = open(sys.argv[2]).read()
anchor = "    location = /lander"
assert c.count(anchor) == 1, "nginx 配置里找不到插入位置"
open(sys.argv[1], "w").write(c.replace(anchor, loc + anchor))
PY
fi
if ! grep -q "location \^~ /rpc/" $C; then
  sudo cp $C $C.bak-$(date +%Y%m%d%H%M%S)
  sudo python3 - "$C" /tmp/tf/deploy/nginx-location.conf <<PY
import sys
c = open(sys.argv[1]).read(); loc = open(sys.argv[2]).read()
if "api/relay/sign" in c:
    loc = loc[loc.index("    # RPC 中转"):]
anchor = "    location = /lander"
assert c.count(anchor) == 1, "nginx 配置里找不到插入位置"
open(sys.argv[1], "w").write(c.replace(anchor, loc + anchor))
PY
fi
sudo nginx -t
sudo systemctl daemon-reload
sudo systemctl enable tapetape-files >/dev/null 2>&1
sudo systemctl restart tapetape-files
sudo systemctl reload nginx
rm -rf /tmp/tf /tmp/files-backend.tgz
sleep 2
echo "服务状态：$(systemctl is-active tapetape-files)"'
sleep 1
echo "线上检查（没写密钥时应是 503 not-configured，写了以后是电路名和节点）："
curl -s -w "  HTTP %{http_code}\n" https://tapetape.org/api/relay/sign
curl -s -w "  HTTP %{http_code}\n" -H 'content-type: application/json' -d '{"jsonrpc":"2.0","id":1,"method":"eth_chainId","params":[]}' https://tapetape.org/rpc/196
curl -s -o /dev/null -w "官网首页 HTTP %{http_code}\n" https://tapetape.org/
