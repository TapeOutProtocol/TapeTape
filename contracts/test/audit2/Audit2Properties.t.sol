// SPDX-License-Identifier: MIT
pragma solidity 0.8.28;

import {Test} from "forge-std/Test.sol";
import {MessageHashUtils} from "@openzeppelin/contracts/utils/cryptography/MessageHashUtils.sol";
import {TapeTapeRedPacket} from "../../src/TapeTapeRedPacket.sol";
import {Token} from "../TapeTapeRedPacket.t.sol";

/// 第二轮审计补充：原有不变量只看「合约余额 == 欠款」，看不出钱发给了谁。这里额外记一本「谁该收到多少」的影子账，
/// 并核对视图（getClaimedAmount / getClaimRecords / remain*）与实际转账一致。
contract Ledger is Test {
    TapeTapeRedPacket public rp;
    Token public usdt;
    uint256 constant PK = 0xD00D;
    address[] public creators;
    string[] public ids;
    uint256 n;
    mapping(address => uint256) public expectNative;
    mapping(address => uint256) public expectToken;
    address[] public payees;
    mapping(address => bool) seen;

    constructor(TapeTapeRedPacket r, Token t) {
        rp = r;
        usdt = t;
    }

    function _payee(address a) internal {
        if (!seen[a]) {
            seen[a] = true;
            payees.push(a);
        }
    }

    function _credit(address token, address to, uint256 amt) internal {
        _payee(to);
        if (token == address(0)) expectNative[to] += amt;
        else expectToken[to] += amt;
    }

    function create(uint256 who, uint256 amount, uint256 count, bool native, uint256 recSeed) external {
        address c = address(uint160(0x7000 + (who % 4)));
        count = bound(count, 1, 12);
        amount = bound(amount, 2 * count, 1e24);
        address recipient = (count == 1 && recSeed % 2 == 0) ? address(uint160(0xA000 + (recSeed % 8))) : address(0);
        string memory id = string.concat("p-", vm.toString(n++));
        address token = native ? address(0) : address(usdt);
        vm.deal(c, amount);
        usdt.mint(c, amount);
        vm.startPrank(c);
        usdt.approve(address(rp), amount);
        rp.createRedPacket{value: native ? amount : 0}(id, token, amount, count, vm.addr(PK), recipient, 50);
        vm.stopPrank();
        creators.push(c);
        ids.push(id);
    }

    function claim(uint256 i, uint256 who) external {
        if (ids.length == 0) return;
        i = i % ids.length;
        address claimer = address(uint160(0xA000 + (who % 8)));
        bytes32 d = MessageHashUtils.toEthSignedMessageHash(rp.claimDigest(creators[i], ids[i], claimer));
        (uint8 v, bytes32 r, bytes32 s) = vm.sign(PK, d);
        TapeTapeRedPacket.RedPacket memory p = rp.getRedPacketInfo(creators[i], ids[i]);
        vm.prank(claimer);
        try rp.claimRedPacket(creators[i], ids[i], abi.encodePacked(r, s, v)) returns (uint256 got) {
            _credit(p.token, claimer, got);
            assertTrue(p.recipient == address(0) || p.recipient == claimer, "only recipient");
            assertEq(rp.getClaimedAmount(creators[i], ids[i], claimer), got, "claimed view");
        } catch {}
    }

    function refund(uint256 i, uint256 dt, uint256 toSeed) external {
        if (ids.length == 0) return;
        i = i % ids.length;
        vm.warp(block.timestamp + bound(dt, 0, 10 days));
        address to = address(uint160(0xB000 + (toSeed % 4)));
        TapeTapeRedPacket.RedPacket memory p = rp.getRedPacketInfo(creators[i], ids[i]);
        vm.prank(creators[i]);
        try rp.refundRedPacket(ids[i], to) returns (uint256 got) {
            assertGe(block.timestamp, p.createTime + 7 days, "refund too early");
            assertEq(got, p.remainAmount, "refund = remain");
            _credit(p.token, to, got);
        } catch {}
    }

    function stranger(uint256 i, uint256 dt) external {
        // 外人（没有领取密钥、不是创建者）任何操作都不应拿到钱
        if (ids.length == 0) return;
        i = i % ids.length;
        vm.warp(block.timestamp + bound(dt, 0, 10 days));
        address x = address(0xBAD);
        vm.startPrank(x);
        try rp.refundRedPacket(ids[i], x) {} catch {}
        bytes32 d = MessageHashUtils.toEthSignedMessageHash(rp.claimDigest(creators[i], ids[i], x));
        (uint8 v, bytes32 r, bytes32 s) = vm.sign(0xBADBAD, d);
        try rp.claimRedPacket(creators[i], ids[i], abi.encodePacked(r, s, v)) {} catch {}
        try rp.withdrawFees(address(0), x) {} catch {}
        vm.stopPrank();
    }

    function payeeCount() external view returns (uint256) {
        return payees.length;
    }

    function idCount() external view returns (uint256) {
        return ids.length;
    }

    function checkViews() external view {
        for (uint256 i = 0; i < ids.length; i++) {
            TapeTapeRedPacket.RedPacket memory p = rp.getRedPacketInfo(creators[i], ids[i]);
            TapeTapeRedPacket.ClaimRecord[] memory recs = rp.getClaimRecords(creators[i], ids[i]);
            uint256 sum;
            for (uint256 j = 0; j < recs.length; j++) sum += recs[j].amount;
            if (!p.refunded) {
                assertEq(sum + p.remainAmount, p.totalAmount, "records + remain = total");
                assertEq(recs.length + p.remainCount, p.totalCount, "records + remainCount = count");
            } else {
                assertEq(p.remainAmount, 0);
                assertLe(sum, p.totalAmount);
            }
        }
    }
}

contract Audit2Invariant is Test {
    TapeTapeRedPacket rp;
    Token usdt;
    Ledger h;

    function setUp() public {
        rp = new TapeTapeRedPacket(address(this), 7);
        usdt = new Token(18);
        h = new Ledger(rp, usdt);
        targetContract(address(h));
    }

    function invariant_everyPayeeGotExactlyWhatTheLedgerSays() public view {
        for (uint256 i = 0; i < h.payeeCount(); i++) {
            address a = h.payees(i);
            assertEq(a.balance, h.expectNative(a), "native payee");
            assertEq(usdt.balanceOf(a), h.expectToken(a), "token payee");
        }
        assertEq(address(0xBAD).balance, 0, "stranger native");
        assertEq(usdt.balanceOf(address(0xBAD)), 0, "stranger token");
    }

    function invariant_viewsConsistent() public view {
        h.checkViews();
    }
}

contract Audit2Unit is Test {
    TapeTapeRedPacket rp;
    Token usdt;
    address alice = address(0xA11CE);
    address bob = address(0xB0B);
    uint256 pk = 0xC1A1;

    event RedPacketCreated(bytes32 indexed key, address indexed creator, string id, address token, uint256 amount, uint256 fee, uint256 count, address claimKey, address recipient);
    event RedPacketClaimed(bytes32 indexed key, address indexed claimer, uint256 amount);
    event RedPacketRefunded(bytes32 indexed key, address indexed creator, address to, uint256 amount);

    function setUp() public {
        rp = new TapeTapeRedPacket(address(this), 10);
        usdt = new Token(18);
        usdt.mint(alice, 1e24);
        vm.prank(alice);
        usdt.approve(address(rp), type(uint256).max);
    }

    function _sig(uint256 k, string memory id, address who) internal view returns (bytes memory) {
        bytes32 d = MessageHashUtils.toEthSignedMessageHash(rp.claimDigest(alice, id, who));
        (uint8 v, bytes32 r, bytes32 s) = vm.sign(k, d);
        return abi.encodePacked(r, s, v);
    }

    /// 事件字段就是客户端要用的：净额、手续费、领取额、退款去向。
    function test_eventsCarryNetClaimAndRefundTarget() public {
        bytes32 key = rp.packetKey(alice, "e1");
        vm.expectEmit(true, true, false, true, address(rp));
        emit RedPacketCreated(key, alice, "e1", address(usdt), 990, 10, 2, vm.addr(pk), address(0));
        vm.prank(alice);
        rp.createRedPacket("e1", address(usdt), 1000, 2, vm.addr(pk), address(0), 10);

        bytes memory s = _sig(pk, "e1", bob);
        vm.recordLogs();
        vm.prank(bob);
        uint256 got = rp.claimRedPacket(alice, "e1", s);
        assertEq(usdt.balanceOf(bob), got);
        vm.warp(block.timestamp + 7 days);
        vm.expectEmit(true, true, false, true, address(rp));
        emit RedPacketRefunded(key, alice, address(0xD00D), 990 - got);
        vm.prank(alice);
        rp.refundRedPacket("e1", address(0xD00D));
        assertEq(usdt.balanceOf(address(0xD00D)), 990 - got);
    }

    /// 1:1 转账也必须有领取密钥签名：客户端若没把领取私钥交给收款人（或收款人丢了消息），收款人永远领不了，只能等 7 天后发送者退回。
    // 第二轮建议 C-2 已修：转账只认收款人，不需要领取签名。
    function test_transferNeedsNoSignatureOnlyRecipient() public {
        vm.prank(alice);
        rp.createRedPacket("t1", address(usdt), 1000, 1, vm.addr(pk), bob, 10);
        // 持有领取密钥的第三方也领不了
        bytes memory forCarol = _sig(pk, "t1", address(0xCA401));
        vm.prank(address(0xCA401));
        vm.expectRevert(TapeTapeRedPacket.NotRecipient.selector);
        rp.claimRedPacket(alice, "t1", forCarol);
        vm.prank(bob);
        assertEq(rp.claimRedPacket(alice, "t1", ""), 990);
    }

    /// 手续费在创建时一次收走，红包没人领、全额退回时手续费也不退（客户端需要向用户说明）。
    function test_feeNotReturnedOnFullRefund() public {
        vm.prank(alice);
        rp.createRedPacket("f1", address(usdt), 1000, 3, vm.addr(pk), address(0), 10);
        vm.warp(block.timestamp + 7 days);
        uint256 b = usdt.balanceOf(alice);
        vm.prank(alice);
        rp.refundRedPacket("f1", alice);
        assertEq(usdt.balanceOf(alice) - b, 990);
        assertEq(rp.collectedFees(address(usdt)), 10);
    }

    /// 同一个 ID 大小写不同是两个红包（客户端要固定大小写）。
    function test_idIsCaseSensitive() public {
        vm.startPrank(alice);
        rp.createRedPacket("abc-1", address(usdt), 1000, 1, vm.addr(pk), address(0), 10);
        rp.createRedPacket("ABC-1", address(usdt), 1000, 1, vm.addr(pk), address(0), 10);
        vm.stopPrank();
        assertTrue(rp.packetKey(alice, "abc-1") != rp.packetKey(alice, "ABC-1"));
    }
}
