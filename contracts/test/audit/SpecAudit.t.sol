// SPDX-License-Identifier: MIT
pragma solidity 0.8.28;

import {Test, Vm, console2} from "forge-std/Test.sol";
import {ERC20} from "@openzeppelin/contracts/token/ERC20/ERC20.sol";
import {MessageHashUtils} from "@openzeppelin/contracts/utils/cryptography/MessageHashUtils.sol";
import {TapeTapeRedPacket} from "../../src/TapeTapeRedPacket.sol";

contract AToken is ERC20 {
    constructor() ERC20("T", "T") {}

    function mint(address to, uint256 a) external {
        _mint(to, a);
    }

    function burnFrom(address who, uint256 a) external {
        _burn(who, a);
    }
}

/// Token that can lose balance after deposit (negative rebase / owner burn / blacklist-seize).
/// Passes the "received == amount" check at creation.
contract ShrinkToken is AToken {}

abstract contract AuditBase is Test {
    TapeTapeRedPacket rp;
    AToken tok;
    address owner = makeAddr("owner");
    address alice = makeAddr("alice");
    address bob = makeAddr("bob");
    uint256 keyPk = 0xA11CE;
    address claimKey;

    function setUp() public virtual {
        rp = new TapeTapeRedPacket(owner, 5);
        tok = new AToken();
        claimKey = vm.addr(keyPk);
        tok.mint(alice, type(uint256).max / 2);
        vm.deal(alice, 1e30);
        vm.prank(alice);
        tok.approve(address(rp), type(uint256).max);
    }

    function sig(address creator, string memory id, address claimer) internal view returns (bytes memory) {
        bytes32 d = MessageHashUtils.toEthSignedMessageHash(rp.claimDigest(creator, id, claimer));
        (uint8 v, bytes32 r, bytes32 s) = vm.sign(keyPk, d);
        return abi.encodePacked(r, s, v);
    }

    function mk(string memory id, uint256 amount, uint256 count) internal returns (bytes32) {
        vm.prank(alice);
        return rp.createRedPacket(id, address(tok), amount, count, claimKey, address(0), 50);
    }

    function claimAs(address who, string memory id) internal returns (uint256) {
        bytes memory s = sig(alice, id, who);
        vm.prank(who);
        return rp.claimRedPacket(alice, id, s);
    }
}

contract SpecBoundaryAudit is AuditBase {
    // ---- id length / encoding ----
    function test_id64ok_65reverts() public {
        string memory id64 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        mk(id64, 100, 1);
        string memory id65 = string.concat(id64, "x");
        vm.prank(alice);
        vm.expectRevert(TapeTapeRedPacket.InvalidId.selector);
        rp.createRedPacket(id65, address(tok), 100, 1, claimKey, address(0), 50);
        // UUID (36 bytes) lowercase vs uppercase are different packets
        mk("8f14e45f-ceea-467a-9f0b-7c1b2d3e4f50", 100, 1);
        mk("8F14E45F-CEEA-467A-9F0B-7C1B2D3E4F50", 100, 1);
    }

    function test_idLength64Ok65Rejected() public {
        string memory s64 = "";
        for (uint256 i; i < 64; i++) s64 = string.concat(s64, "a");
        mk(s64, 100, 1);
        vm.prank(alice);
        vm.expectRevert(TapeTapeRedPacket.InvalidId.selector);
        rp.createRedPacket(string.concat(s64, "a"), address(tok), 100, 1, claimKey, address(0), 50);
        mk("8F14E45F-CEEA-467A-9F0B-7C1B2D3E4F50", 100, 1);
    }

    function test_nonAsciiIdRejected() public {
        bytes memory badB = hex"80ff"; string memory bad = string(badB);
        vm.prank(alice);
        vm.expectRevert(TapeTapeRedPacket.InvalidId.selector);
        rp.createRedPacket(bad, address(tok), 100, 1, claimKey, address(0), 50);
        vm.prank(alice);
        vm.expectRevert(TapeTapeRedPacket.InvalidId.selector);
        rp.createRedPacket(unicode"中", address(tok), 100, 1, claimKey, address(0), 50);
        vm.prank(alice);
        vm.expectRevert(TapeTapeRedPacket.InvalidId.selector);
        rp.createRedPacket("a b", address(tok), 100, 1, claimKey, address(0), 50);
        mk("Az09-", 100, 1);
    }

    // ---- amount / count / fee edges ----
    function test_count1_amount1() public {
        mk("a", 1, 1); // fee floor(1*5/1000)=0
        assertEq(claimAs(bob, "a"), 1);
    }

    function test_count1000_amountEqualsCount_feeZeroOnlyWhenRateZero() public {
        // at 5 per mille, amount == count == 1000 -> fee 5 -> net 995 < 1000 -> revert
        vm.prank(alice);
        vm.expectRevert(TapeTapeRedPacket.AmountTooSmall.selector);
        rp.createRedPacket("x", address(tok), 1000, 1000, claimKey, address(0), 50);
        // smallest gross that works at 5 per mille: 1005 (fee 5, net 1000); 1004 fails (fee 5, net 999)
        vm.prank(alice);
        vm.expectRevert(TapeTapeRedPacket.AmountTooSmall.selector);
        rp.createRedPacket("x", address(tok), 1004, 1000, claimKey, address(0), 50);
        mk("x", 1005, 1000);
        for (uint256 i; i < 1000; i++) {
            assertEq(claimAs(address(uint160(0xB000 + i)), "x"), 1);
        }
        // rate 0: amount == count works
        vm.prank(owner);
        rp.setFeeRate(0);
        mk("y", 1000, 1000);
    }

    function test_feeRoundsDownToZeroBelowThreshold() public {
        // rate 5: any gross < 200 units pays zero fee; rate 50: < 20 units
        bytes32 k = mk("f", 199, 1);
        k;
        assertEq(rp.getRedPacketInfo(alice, "f").fee, 0);
        mk("g", 200, 1);
        assertEq(rp.getRedPacketInfo(alice, "g").fee, 1);
        vm.prank(owner);
        rp.setFeeRate(50);
        mk("h", 19, 1);
        assertEq(rp.getRedPacketInfo(alice, "h").fee, 0);
        mk("i", 20, 1);
        assertEq(rp.getRedPacketInfo(alice, "i").fee, 1);
    }

    function test_feeRateMaxAndRace() public {
        vm.prank(owner);
        rp.setFeeRate(50);
        vm.prank(alice);
        vm.expectRevert(TapeTapeRedPacket.FeeRateTooHigh.selector);
        rp.createRedPacket("r", address(tok), 1000, 1, claimKey, address(0), 49);
        vm.prank(alice);
        rp.createRedPacket("r", address(tok), 1000, 1, claimKey, address(0), 50);
        assertEq(rp.getRedPacketInfo(alice, "r").fee, 50);
        vm.prank(owner);
        vm.expectRevert(TapeTapeRedPacket.FeeRateTooHigh.selector);
        rp.setFeeRate(51);
    }

    function test_hugeAmountOverflowsWithPanicNotCustomError() public {
        // amount * rate overflows for amount > 2^256/5: Solidity panic 0x11 instead of a named error
        vm.prank(alice);
        vm.expectRevert(abi.encodeWithSignature("Panic(uint256)", 0x11));
        rp.createRedPacket("big", address(tok), type(uint256).max / 4, 1, claimKey, address(0), 50);
    }

    function test_amountAboveU128Representable() public {
        // a packet whose amounts do not fit in u128 (client decoder rejects any word with high bytes)
        vm.prank(owner);
        rp.setFeeRate(0);
        uint256 a = uint256(type(uint128).max) + 1;
        mk("u", a, 1);
        assertEq(rp.getRedPacketInfo(alice, "u").totalAmount, a);
    }

    function test_minAmountComparesGross() public {
        vm.prank(owner);
        rp.setMinAmount(address(tok), 1000);
        // gross 1000 passes min although net after fee is 995
        mk("m", 1000, 1);
        assertEq(rp.getRedPacketInfo(alice, "m").totalAmount, 995);
    }

    function test_minAmountNativeIsAddressZero() public {
        vm.prank(owner);
        rp.setMinAmount(address(0), 1 ether);
        vm.prank(alice);
        vm.expectRevert(TapeTapeRedPacket.AmountTooSmall.selector);
        rp.createRedPacket{value: 1 ether - 1}("n", address(0), 1 ether - 1, 1, claimKey, address(0), 50);
        vm.prank(alice);
        rp.createRedPacket{value: 1 ether}("n", address(0), 1 ether, 1, claimKey, address(0), 50);
    }

    function test_nativeValueMismatchAndValueWithToken() public {
        vm.prank(alice);
        vm.expectRevert(TapeTapeRedPacket.IncorrectValue.selector);
        rp.createRedPacket{value: 99}("v", address(0), 100, 1, claimKey, address(0), 50);
        vm.prank(alice);
        vm.expectRevert(TapeTapeRedPacket.IncorrectValue.selector);
        rp.createRedPacket{value: 1}("v", address(tok), 100, 1, claimKey, address(0), 50);
        vm.prank(alice);
        vm.expectRevert(TapeTapeRedPacket.UnsupportedToken.selector);
        rp.createRedPacket("v", makeAddr("eoa"), 100, 1, claimKey, address(0), 50);
    }

    // ---- random split ----
    function test_shareNeverExceedsTwiceAverage() public {
        mk("s", 1_000_000, 10);
        uint256 remainAmt = rp.getRedPacketInfo(alice, "s").totalAmount;
        uint256 remainCnt = 10;
        for (uint256 i; i < 10; i++) {
            vm.prevrandao(bytes32(uint256(keccak256(abi.encode(i)))));
            uint256 a = claimAs(address(uint160(0xC000 + i)), "s");
            if (remainCnt > 1) assertLe(a, (remainAmt * 2) / remainCnt);
            remainAmt -= a;
            remainCnt--;
        }
        assertEq(remainAmt, 0);
    }

    // ---- 7-day boundary ----
    function test_refundBoundaryExactSecond() public {
        mk("t", 1000, 2);
        uint256 t0 = block.timestamp;
        vm.warp(t0 + 7 days - 1);
        vm.prank(alice);
        vm.expectRevert(TapeTapeRedPacket.RefundTooEarly.selector);
        rp.refundRedPacket("t", alice);
        vm.warp(t0 + 7 days);
        // claim still allowed at the exact boundary before refund
        claimAs(bob, "t");
        vm.prank(alice);
        uint256 back = rp.refundRedPacket("t", alice);
        assertGt(back, 0);
        bytes memory s = sig(alice, "t", makeAddr("late"));
        vm.prank(makeAddr("late"));
        vm.expectRevert(TapeTapeRedPacket.PacketRefunded.selector);
        rp.claimRedPacket(alice, "t", s);
        // after refund the info shows refunded=true, remainCount=0, remainAmount=0
        TapeTapeRedPacket.RedPacket memory p = rp.getRedPacketInfo(alice, "t");
        assertTrue(p.refunded);
        assertEq(p.remainCount, 0);
        assertEq(p.remainAmount, 0);
        // second refund
        vm.prank(alice);
        vm.expectRevert(TapeTapeRedPacket.PacketRefunded.selector);
        rp.refundRedPacket("t", alice);
    }

    // ---- signatures ----
    function test_vZeroOneRejected_clientMustUse27or28() public {
        mk("k", 1000, 2);
        bytes memory s = sig(alice, "k", bob);
        uint8 v = uint8(s[64]);
        s[64] = bytes1(v - 27);
        vm.prank(bob);
        vm.expectRevert(TapeTapeRedPacket.InvalidSignature.selector);
        rp.claimRedPacket(alice, "k", s);
    }

    function test_compact64ByteSignatureRejected() public {
        mk("k", 1000, 2);
        bytes32 d = MessageHashUtils.toEthSignedMessageHash(rp.claimDigest(alice, "k", bob));
        (bytes32 r, bytes32 vs) = vm.signCompact(keyPk, d);
        vm.prank(bob);
        vm.expectRevert(TapeTapeRedPacket.InvalidSignature.selector);
        rp.claimRedPacket(alice, "k", abi.encodePacked(r, vs));
    }

    function test_unprefixedSignatureRejected() public {
        mk("k", 1000, 2);
        (uint8 v, bytes32 r, bytes32 s) = vm.sign(keyPk, rp.claimDigest(alice, "k", bob));
        vm.prank(bob);
        vm.expectRevert(TapeTapeRedPacket.InvalidSignature.selector);
        rp.claimRedPacket(alice, "k", abi.encodePacked(r, s, v));
    }

    function test_creatorCanClaimOwnTransferImmediately() public {
        // a "transfer" (count 1) has no bound recipient: the creator holds the claim key and can claw it back
        mk("tr", 1000, 1);
        bytes memory s = sig(alice, "tr", alice);
        vm.prank(alice);
        assertEq(rp.claimRedPacket(alice, "tr", s), 995);
    }

    function test_claimKeyReuseAcrossPacketsIsolated() public {
        mk("p1", 1000, 2);
        mk("p2", 1000, 2);
        bytes memory s1 = sig(alice, "p1", bob);
        vm.prank(bob);
        vm.expectRevert(TapeTapeRedPacket.InvalidSignature.selector);
        rp.claimRedPacket(alice, "p2", s1);
    }

    function test_wrongCreatorParamIsNoSuchPacket() public {
        mk("w", 1000, 2);
        bytes memory s = sig(bob, "w", bob);
        vm.prank(bob);
        vm.expectRevert(TapeTapeRedPacket.NoSuchPacket.selector);
        rp.claimRedPacket(bob, "w", s);
    }

    // ---- digest exact byte layout (for the Rust client) ----
    function test_digestIsSixWordConcat() public view {
        address creator = address(0x2222222222222222222222222222222222222222);
        address claimer = address(0x3333333333333333333333333333333333333333);
        string memory id = "8F14E45F-CEEA-467A-9F0B-7C1B2D3E4F50";
        bytes memory raw = bytes.concat(
            rp.CLAIM_TAG(),
            bytes32(block.chainid),
            bytes32(uint256(uint160(address(rp)))),
            bytes32(uint256(uint160(creator))),
            keccak256(bytes(id)),
            bytes32(uint256(uint160(claimer)))
        );
        assertEq(raw.length, 192);
        assertEq(keccak256(raw), rp.claimDigest(creator, id, claimer));
        assertEq(rp.CLAIM_TAG(), keccak256("TapeTapeRedPacket.claim.v1"));
    }

    /// Fixed vector the Rust client can copy into a unit test.
    function test_printClientVector() public {
        address at = 0x1111111111111111111111111111111111111111;
        deployCodeTo("TapeTapeRedPacket.sol:TapeTapeRedPacket", abi.encode(owner, uint256(5)), at);
        vm.chainId(56);
        TapeTapeRedPacket c = TapeTapeRedPacket(at);
        address creator = 0x2222222222222222222222222222222222222222;
        address claimer = 0x3333333333333333333333333333333333333333;
        string memory id = "8F14E45F-CEEA-467A-9F0B-7C1B2D3E4F50";
        bytes32 inner = c.claimDigest(creator, id, claimer);
        bytes32 eth = MessageHashUtils.toEthSignedMessageHash(inner);
        (uint8 v, bytes32 r, bytes32 s) = vm.sign(1, eth);
        console2.log("claim key address (pk=1):", vm.addr(1));
        console2.log("packetKey:");
        console2.logBytes32(c.packetKey(creator, id));
        console2.log("inner digest:");
        console2.logBytes32(inner);
        console2.log("eip191 digest:");
        console2.logBytes32(eth);
        console2.log("signature r|s|v:");
        console2.logBytes(abi.encodePacked(r, s, v));
    }

    function test_packetKeyLayout() public view {
        address creator = address(0x2222222222222222222222222222222222222222);
        string memory id = "8F14E45F-CEEA-467A-9F0B-7C1B2D3E4F50";
        bytes memory raw = bytes.concat(
            bytes32(uint256(uint160(creator))), bytes32(uint256(0x40)), bytes32(bytes(id).length), bytes(id), new bytes(28)
        );
        assertEq(raw.length, 32 * 3 + 64);
        assertEq(keccak256(raw), rp.packetKey(creator, id));
    }

    // ---- view ABI layout ----
    function test_infoAbiIsElevenStaticWordsNoOffset() public {
        (bool ok, bytes memory out) =
            address(rp).staticcall(abi.encodeCall(TapeTapeRedPacket.getRedPacketInfo, (alice, "none")));
        assertTrue(ok);
        assertEq(out.length, 352);
        assertEq(keccak256(out), keccak256(new bytes(352)), "unknown packet = all zero");
        mk("L", 1000, 3);
        (, out) = address(rp).staticcall(abi.encodeCall(TapeTapeRedPacket.getRedPacketInfo, (alice, "L")));
        assertEq(out.length, 352);
        uint256[11] memory w = abi.decode(out, (uint256[11]));
        assertEq(address(uint160(w[0])), alice); // creator
        assertEq(address(uint160(w[1])), address(tok)); // token
        assertEq(address(uint160(w[2])), claimKey); // claimKey
        assertEq(w[3], 0); // recipient
        assertEq(w[4], 995); // totalAmount (net)
        assertEq(w[5], 995); // remainAmount
        assertEq(w[6], 5); // fee
        assertEq(w[7], 3); // totalCount
        assertEq(w[8], 3); // remainCount
        assertEq(w[9], block.timestamp); // createTime
        assertEq(w[10], 0); // refunded
        assertEq(w[4] + w[6], 1000, "gross = totalAmount + fee");
    }

    function test_recordsAbiLayout() public {
        (bool ok, bytes memory out) =
            address(rp).staticcall(abi.encodeCall(TapeTapeRedPacket.getClaimRecords, (alice, "none")));
        assertTrue(ok);
        assertEq(out.length, 64);
        assertEq(abi.decode(out, (uint256[2]))[0], 0x20);
        mk("R", 1000, 3);
        uint256 a = claimAs(bob, "R");
        (, out) = address(rp).staticcall(abi.encodeCall(TapeTapeRedPacket.getClaimRecords, (alice, "R")));
        assertEq(out.length, 64 + 64);
        uint256[4] memory w = abi.decode(out, (uint256[4]));
        assertEq(w[0], 0x20);
        assertEq(w[1], 1);
        assertEq(address(uint160(w[2])), bob);
        assertEq(w[3], a);
    }

    // ---- token risk: balance shrink after deposit breaks other packets of the same token ----
    function test_shrinkingTokenMakesOtherPacketsInsolvent() public {
        ShrinkToken st = new ShrinkToken();
        st.mint(alice, 2000);
        vm.prank(alice);
        st.approve(address(rp), type(uint256).max);
        vm.prank(owner);
        rp.setFeeRate(0);
        vm.prank(alice);
        rp.createRedPacket("A", address(st), 1000, 1, claimKey, address(0), 50);
        vm.prank(alice);
        rp.createRedPacket("B", address(st), 1000, 1, claimKey, address(0), 50);
        // token-side burn/seize of 500 from the escrow (negative rebase, blacklist seize, etc.)
        st.burnFrom(address(rp), 500);
        claimAs(bob, "A"); // first claimer gets full 1000 from the pooled balance
        bytes memory s = sig(alice, "B", makeAddr("c"));
        vm.prank(makeAddr("c"));
        vm.expectRevert();
        rp.claimRedPacket(alice, "B", s); // packet B is stuck: only 500 left
    }

    function test_renounceOwnershipDisabled() public {
        mk("z", 1000, 1);
        vm.prank(owner);
        vm.expectRevert(TapeTapeRedPacket.RenounceDisabled.selector);
        rp.renounceOwnership();
        vm.prank(bob);
        vm.expectRevert();
        rp.renounceOwnership();
    }

    function test_directTransfersAreStuck() public {
        tok.mint(address(this), 77);
        tok.transfer(address(rp), 77);
        vm.prank(owner);
        vm.expectRevert(TapeTapeRedPacket.NothingToWithdraw.selector);
        rp.withdrawFees(address(tok), owner);
    }

    function test_createReturnsKeyEqualToPacketKeyAndEventTopic() public {
        vm.recordLogs();
        bytes32 k = mk("e", 1000, 2);
        assertEq(k, rp.packetKey(alice, "e"));
        Vm.Log[] memory logs = vm.getRecordedLogs();
        assertEq(logs[logs.length - 1].topics[1], k);
        assertEq(logs[logs.length - 1].topics[0], keccak256("RedPacketCreated(bytes32,address,string,address,uint256,uint256,uint256,address,address)"));
    }
}

/// Gas numbers. Run with: forge test --isolate --match-contract GasAudit -vv
contract GasAudit is AuditBase {
    function test_gasCreateAndLastClaimsOf1000() public {
        string memory id = "8F14E45F-CEEA-467A-9F0B-7C1B2D3E4F508F14E45F-CEEA-467A-9F0B-7C1B"; // 64 bytes
        vm.prank(alice);
        uint256 g = gasleft();
        rp.createRedPacket(id, address(tok), 1e24, 1000, claimKey, address(0), 50);
        console2.log("create ERC20 count=1000 id=64B execution gas:", g - gasleft());
        vm.prank(alice);
        g = gasleft();
        rp.createRedPacket{value: 1 ether}("nat", address(0), 1 ether, 1, claimKey, address(0), 50);
        console2.log("create native count=1 execution gas:", g - gasleft());
        uint256 maxClaim;
        for (uint256 i; i < 1000; i++) {
            address who = address(uint160(0xD0000 + i));
            bytes memory s = sig(alice, id, who);
            vm.prank(who);
            g = gasleft();
            rp.claimRedPacket(alice, id, s);
            g = g - gasleft();
            if (g > maxClaim) maxClaim = g;
            if (i == 0 || i >= 997) console2.log("claim #", i + 1, "gas:", g);
        }
        console2.log("max claim gas:", maxClaim);
        uint256 g0 = gasleft();
        TapeTapeRedPacket.ClaimRecord[] memory recs = rp.getClaimRecords(alice, id);
        console2.log("getClaimRecords(1000) gas (in-test, approx):", g0 - gasleft());
        assertEq(recs.length, 1000);
    }
}
