#!/usr/bin/env bash
# 审计用变异测试：把合约复制到临时目录，逐个改坏一处关键检查，看现有测试会不会失败。
# 不碰 contracts/src/。用法：bash test/audit2/mutate.sh [临时目录]
set -uo pipefail
HERE="$(cd "$(dirname "$0")/../.." && pwd)"
WORK="${1:-$(mktemp -d)}"
rm -rf "$WORK/c"; mkdir -p "$WORK/c"
rsync -a --exclude out --exclude cache --exclude lib "$HERE/" "$WORK/c/"
ln -s "$HERE/lib" "$WORK/c/lib"
SRC="$WORK/c/src/TapeTapeRedPacket.sol"
cp "$SRC" "$WORK/orig.sol"

# 名称|要替换的原文（perl 字面量）|替换成
MUTANTS=(
'M01 去掉「每人只能领一次」|if (_claimed[key][msg.sender] != 0) revert AlreadyClaimed();|'
'M02 签名不绑定领取人|keccak256(bytes(id)), claimer)|keccak256(bytes(id)), address(0))'
'M03 签名不绑定链|block.chainid, address(this)|uint256(56), address(this)'
'M04 签名不绑定合约|block.chainid, address(this)|block.chainid, address(0)'
'M05 去掉指定收款人检查|if (p.recipient != address(0) && msg.sender != p.recipient) revert NotRecipient();|'
'M06 去掉退款等待期|if (block.timestamp < p.createTime + REFUND_DELAY) revert RefundTooEarly();|'
'M07 退款边界 < 改 <=|block.timestamp < p.createTime + REFUND_DELAY|block.timestamp <= p.createTime + REFUND_DELAY'
'M08 去掉到账核对|if (IERC20(token).balanceOf(address(this)) - before != amount) revert UnsupportedToken();|'
'M09 去掉 maxFeeRate 检查|if (rate > maxFeeRate) revert FeeRateTooHigh();|'
'M10 去掉份额上限 cap|if (amount > cap) amount = cap;|'
'M11 领取的钱发给创建者|_send(p.token, msg.sender, amount);|_send(p.token, p.creator, amount);'
'M12 手续费记成全额|collectedFees[token] += fee;|collectedFees[token] += amount;'
'M13 提手续费去掉 onlyOwner|function withdrawFees(address token, address to) external onlyOwner nonReentrant|function withdrawFees(address token, address to) external nonReentrant'
'M14 去掉签名人比对|signer != p.claimKey|signer == address(1)'
'M15 去掉 net>=count|if (net < count) revert AmountTooSmall();|'
'M16 原生币不核对 msg.value|if (msg.value != amount) revert IncorrectValue();|'
'M17 领取去掉 nonReentrant|external\n        nonReentrant\n        returns (uint256 amount)|external\n        returns (uint256 amount)'
'M18 领取不检查已退款|if (p.refunded) revert PacketRefunded();\n        if (p.remainCount == 0)|if (p.remainCount == 0)'
'M19 退款不清 remainCount|p.remainCount = 0;\n\n        _send(p.token, to, amount);|_send(p.token, to, amount);'
'M20 最小金额检查失效|if (amount < minAmount[token]) revert AmountTooSmall();|'
'M22 创建事件报毛额|emit RedPacketCreated(key, msg.sender, id, token, net,|emit RedPacketCreated(key, msg.sender, id, token, amount,'
'M23 退款事件去向写成创建者|emit RedPacketRefunded(key, msg.sender, to, amount);|emit RedPacketRefunded(key, msg.sender, msg.sender, amount);'
'M24 退款忽略 to 参数|_send(p.token, to, amount);\n        emit RedPacketRefunded|_send(p.token, msg.sender, amount);\n        emit RedPacketRefunded'
'M25 领取记录金额错|records[i] = ClaimRecord({claimer: list[i], amount: _claimed[key][list[i]]});|records[i] = ClaimRecord({claimer: list[i], amount: 1});'
'M26 hasClaimed 恒为假|return _claimed[packetKey(creator, id)][user] != 0;|return false;'
'M27 指定收款人可配多份|if (recipient != address(0) && count != 1) revert InvalidCount();|'
'M28 不检查 claimKey 非零|if (claimKey == address(0)) revert InvalidClaimKey();|'
'M29 不检查代币有代码|if (token.code.length == 0) revert UnsupportedToken();|'
'M30 setMinAmount 去掉 onlyOwner|function setMinAmount(address token, uint256 amount) external onlyOwner|function setMinAmount(address token, uint256 amount) external'
'M31 setFeeRate 去掉上限|if (newRate > MAX_FEE_RATE) revert FeeRateTooHigh();\n        emit|emit'
'M32 最后一份不给全部剩余|amount = remainAmount;\n        } else {|amount = 1;\n        } else {'
'M33 领取不记 _claimers|_claimers[key].push(msg.sender);|'
'M21 ID 字符集不检查|revert InvalidId();\n            }|}'
)
printf '%-28s %s\n' "变异" "结果"
for m in "${MUTANTS[@]}"; do
  IFS='|' read -r name from to <<<"$m"
  cp "$WORK/orig.sol" "$SRC"
  FROM="$from" TO="$to" perl -0pi -e 'BEGIN{$f=$ENV{FROM};$t=$ENV{TO};$f=~s/\\n/\n/g;$t=~s/\\n/\n/g} $n=s/\Q$f\E/$t/; END{exit($n?0:3)}' "$SRC"
  if [ $? -ne 0 ]; then printf '%-28s %s\n' "$name" "替换没生效（脚本问题）"; continue; fi
  if cmp -s "$SRC" "$WORK/orig.sol"; then printf '%-28s %s\n' "$name" "等价（未改动）"; continue; fi
  OUT=$(cd "$WORK/c" && forge test --no-match-path 'test/audit2/Fork*' 2>&1)
  if echo "$OUT" | grep -q "Compiler run failed\|Error (" ; then printf '%-28s %s\n' "$name" "编译失败"; continue; fi
  FAILS=$(echo "$OUT" | grep -E '^\[FAIL' | sed -E 's/^\[FAIL[^]]*\] ([^ (]+).*/\1/' | sort -u | tr '\n' ' ')
  if [ -n "$FAILS" ]; then printf '%-28s %s\n' "$name" "被抓到：$FAILS"; else printf '%-28s %s\n' "$name" "存活（没有测试失败）"; fi
done
cp "$WORK/orig.sol" "$SRC"
