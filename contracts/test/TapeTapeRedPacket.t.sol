// SPDX-License-Identifier: MIT
pragma solidity 0.8.28;

import {Test} from "forge-std/Test.sol";
import {ERC20} from "@openzeppelin/contracts/token/ERC20/ERC20.sol";
import {MessageHashUtils} from "@openzeppelin/contracts/utils/cryptography/MessageHashUtils.sol";
import {Ownable} from "@openzeppelin/contracts/access/Ownable.sol";
import {TapeTapeRedPacket} from "../src/TapeTapeRedPacket.sol";

contract Token is ERC20 {
    uint8 private immutable _dec;

    constructor(uint8 d) ERC20("T", "T") {
        _dec = d;
    }

    function decimals() public view override returns (uint8) {
        return _dec;
    }

    function mint(address to, uint256 a) external {
        _mint(to, a);
    }
}

contract FeeToken is ERC20 {
    constructor() ERC20("F", "F") {}

    function mint(address to, uint256 a) external {
        _mint(to, a);
    }

    function _update(address from, address to, uint256 value) internal override {
        if (from != address(0) && to != address(0)) {
            super._update(from, address(0), value / 100);
            value -= value / 100;
        }
        super._update(from, to, value);
    }
}

/// Native-coin receiver that tries to re-enter on receipt.
contract Reenterer {
    TapeTapeRedPacket public rp;
    address public creator;
    string public id;
    bytes public sig;
    bool public tried;
    bool public reentered;

    constructor(TapeTapeRedPacket r) {
        rp = r;
    }

    function arm(address c, string calldata i, bytes calldata s) external {
        creator = c;
        id = i;
        sig = s;
    }

    function claim() external {
        rp.claimRedPacket(creator, id, sig);
    }

    receive() external payable {
        if (!tried) {
            tried = true;
            try rp.claimRedPacket(creator, id, sig) {
                reentered = true;
            } catch {}
        }
    }
}

contract Rejecter {
    function claim(TapeTapeRedPacket rp, address c, string calldata i, bytes calldata s) external {
        rp.claimRedPacket(c, i, s);
    }
}

contract Base is Test {
    TapeTapeRedPacket rp;
    Token usdt;
    Token bem;
    address owner = makeAddr("owner");
    address alice = makeAddr("alice");
    address bob = makeAddr("bob");
    address carol = makeAddr("carol");
    uint256 keyPk = 0xC1A1;
    address claimKey;

    function setUp() public virtual {
        rp = new TapeTapeRedPacket(owner, 5);
        usdt = new Token(18);
        bem = new Token(8);
        claimKey = vm.addr(keyPk);
        usdt.mint(alice, 1e30);
        bem.mint(alice, 1e30);
        vm.deal(alice, 1e30);
        vm.prank(alice);
        usdt.approve(address(rp), type(uint256).max);
        vm.prank(alice);
        bem.approve(address(rp), type(uint256).max);
    }

    function sign(uint256 pk, address creator, string memory id, address claimer) internal view returns (bytes memory) {
        bytes32 d = MessageHashUtils.toEthSignedMessageHash(rp.claimDigest(creator, id, claimer));
        (uint8 v, bytes32 r, bytes32 s) = vm.sign(pk, d);
        return abi.encodePacked(r, s, v);
    }

    function create(string memory id, address token, uint256 amount, uint256 count) internal {
        vm.prank(alice);
        if (token == address(0)) {
            rp.createRedPacket{value: amount}(id, token, amount, count, claimKey, address(0), 5);
        } else {
            rp.createRedPacket(id, token, amount, count, claimKey, address(0), 5);
        }
    }

    function claimAs(address who, string memory id) internal returns (uint256) {
        bytes memory s = sign(keyPk, alice, id, who);
        vm.prank(who);
        return rp.claimRedPacket(alice, id, s);
    }
}

contract TapeTapeRedPacketTest is Base {
    function test_createStoresNetAndFee() public {
        create("A", address(usdt), 1000e18, 4);
        TapeTapeRedPacket.RedPacket memory p = rp.getRedPacketInfo(alice, "A");
        assertEq(p.creator, alice);
        assertEq(p.token, address(usdt));
        assertEq(p.claimKey, claimKey);
        assertEq(p.fee, 5e18);
        assertEq(p.totalAmount, 995e18);
        assertEq(p.remainAmount, 995e18);
        assertEq(p.totalCount, 4);
        assertEq(p.remainCount, 4);
        assertEq(p.createTime, block.timestamp);
        assertFalse(p.refunded);
        assertEq(rp.collectedFees(address(usdt)), 5e18);
        assertEq(usdt.balanceOf(address(rp)), 1000e18);
    }

    function test_claimAllShares() public {
        create("A", address(usdt), 1000e18, 3);
        uint256 a = claimAs(bob, "A");
        uint256 b = claimAs(carol, "A");
        uint256 c = claimAs(makeAddr("dave"), "A");
        assertEq(a + b + c, 995e18);
        assertGt(a, 0);
        assertGt(b, 0);
        assertGt(c, 0);
        assertEq(usdt.balanceOf(bob), a);
        TapeTapeRedPacket.ClaimRecord[] memory r = rp.getClaimRecords(alice, "A");
        assertEq(r.length, 3);
        assertEq(r[0].claimer, bob);
        assertEq(r[0].amount, a);
        assertTrue(rp.hasClaimed(alice, "A", carol));
        assertEq(rp.getClaimedAmount(alice, "A", carol), b);
        address eve = makeAddr("eve");
        bytes memory es = sign(keyPk, alice, "A", eve);
        vm.prank(eve);
        vm.expectRevert(TapeTapeRedPacket.NoSharesLeft.selector);
        rp.claimRedPacket(alice, "A", es);
    }

    // The original vulnerability: a signature published on chain must not let anyone else claim.
    function test_signatureCannotBeReusedByAnotherAddress() public {
        create("A", address(usdt), 1000e18, 5);
        bytes memory bobSig = sign(keyPk, alice, "A", bob);
        vm.prank(bob);
        rp.claimRedPacket(alice, "A", bobSig);
        address thief = makeAddr("thief");
        vm.prank(thief);
        vm.expectRevert(TapeTapeRedPacket.InvalidSignature.selector);
        rp.claimRedPacket(alice, "A", bobSig);
    }

    function test_frontRunnerCannotStealTransfer() public {
        create("T", address(0), 1 ether, 1);
        bytes memory bobSig = sign(keyPk, alice, "T", bob);
        address mev = makeAddr("mev");
        vm.prank(mev);
        vm.expectRevert(TapeTapeRedPacket.InvalidSignature.selector);
        rp.claimRedPacket(alice, "T", bobSig);
        vm.prank(bob);
        assertEq(rp.claimRedPacket(alice, "T", bobSig), 0.995 ether);
        assertEq(bob.balance, 0.995 ether);
    }

    // Transfers bound to a recipient: nobody else, not even the creator's other wallets, can claim.
    function test_transferBoundToRecipient() public {
        vm.prank(alice);
        rp.createRedPacket{value: 1 ether}("T", address(0), 1 ether, 1, claimKey, bob, 5);
        assertEq(rp.getRedPacketInfo(alice, "T").recipient, bob);
        address aliceAlt = makeAddr("aliceAlt");
        bytes memory altSig = sign(keyPk, alice, "T", aliceAlt);
        vm.prank(aliceAlt);
        vm.expectRevert(TapeTapeRedPacket.NotRecipient.selector);
        rp.claimRedPacket(alice, "T", altSig);
        bytes memory aliceSig = sign(keyPk, alice, "T", alice);
        vm.prank(alice);
        vm.expectRevert(TapeTapeRedPacket.NotRecipient.selector);
        rp.claimRedPacket(alice, "T", aliceSig);
        // the recipient needs no signature (e.g. lost the chat message)
        vm.prank(bob);
        assertEq(rp.claimRedPacket(alice, "T", ""), 0.995 ether);
        vm.prank(bob);
        vm.expectRevert(TapeTapeRedPacket.NoSharesLeft.selector);
        rp.claimRedPacket(alice, "T", "");
    }

    function test_recipientOnlyForSingleShare() public {
        vm.prank(alice);
        vm.expectRevert(TapeTapeRedPacket.InvalidCount.selector);
        rp.createRedPacket("T", address(usdt), 1000, 2, claimKey, bob, 5);
    }

    function test_unclaimedTransferRefundableAfterDelay() public {
        vm.prank(alice);
        rp.createRedPacket("T", address(usdt), 1000e18, 1, claimKey, bob, 5);
        vm.prank(alice);
        vm.expectRevert(TapeTapeRedPacket.RefundTooEarly.selector);
        rp.refundRedPacket("T", alice);
        vm.warp(block.timestamp + 7 days);
        uint256 before = usdt.balanceOf(alice);
        vm.prank(alice);
        rp.refundRedPacket("T", alice);
        assertEq(usdt.balanceOf(alice), before + 995e18);
    }

    function test_refundToAnotherAddress() public {
        create("A", address(usdt), 1000e18, 2);
        vm.warp(block.timestamp + 7 days);
        address safe = makeAddr("safe");
        vm.prank(alice);
        vm.expectRevert(TapeTapeRedPacket.InvalidRecipient.selector);
        rp.refundRedPacket("A", address(0));
        vm.prank(alice);
        rp.refundRedPacket("A", safe);
        assertEq(usdt.balanceOf(safe), 995e18);
    }

    function test_creatorSignatureIsNotAClaimKey() public {
        create("A", address(usdt), 1000e18, 2);
        uint256 alicePk = 0xA11CE;
        bytes memory s = sign(alicePk, alice, "A", bob);
        vm.prank(bob);
        vm.expectRevert(TapeTapeRedPacket.InvalidSignature.selector);
        rp.claimRedPacket(alice, "A", s);
    }

    function test_signatureBoundToPacketAndContract() public {
        create("A", address(usdt), 1000e18, 2);
        create("B", address(usdt), 1000e18, 2);
        bytes memory forA = sign(keyPk, alice, "A", bob);
        vm.prank(bob);
        vm.expectRevert(TapeTapeRedPacket.InvalidSignature.selector);
        rp.claimRedPacket(alice, "B", forA);

        TapeTapeRedPacket other = new TapeTapeRedPacket(owner, 0);
        assertNotEq(other.claimDigest(alice, "A", bob), rp.claimDigest(alice, "A", bob));
        vm.chainId(196);
        bytes32 on196 = rp.claimDigest(alice, "A", bob);
        vm.chainId(56);
        assertNotEq(on196, rp.claimDigest(alice, "A", bob));
    }

    function test_badSignatureShapesRevert() public {
        create("A", address(usdt), 1000e18, 2);
        vm.startPrank(bob);
        vm.expectRevert(TapeTapeRedPacket.InvalidSignature.selector);
        rp.claimRedPacket(alice, "A", "");
        vm.expectRevert(TapeTapeRedPacket.InvalidSignature.selector);
        rp.claimRedPacket(alice, "A", new bytes(65));
        bytes memory s = sign(keyPk, alice, "A", bob);
        // flip s to the high half: must be rejected (malleability)
        bytes32 r;
        bytes32 ss;
        uint8 v;
        assembly {
            r := mload(add(s, 32))
            ss := mload(add(s, 64))
            v := byte(0, mload(add(s, 96)))
        }
        bytes32 n = 0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141;
        bytes memory high = abi.encodePacked(r, bytes32(uint256(n) - uint256(ss)), v == 27 ? uint8(28) : uint8(27));
        vm.expectRevert(TapeTapeRedPacket.InvalidSignature.selector);
        rp.claimRedPacket(alice, "A", high);
        rp.claimRedPacket(alice, "A", s);
        vm.stopPrank();
    }

    function test_claimTwiceReverts() public {
        create("A", address(usdt), 1000e18, 3);
        claimAs(bob, "A");
        bytes memory s = sign(keyPk, alice, "A", bob);
        vm.prank(bob);
        vm.expectRevert(TapeTapeRedPacket.AlreadyClaimed.selector);
        rp.claimRedPacket(alice, "A", s);
    }

    function test_unknownPacket() public {
        bytes memory s = sign(keyPk, alice, "X", bob);
        vm.prank(bob);
        vm.expectRevert(TapeTapeRedPacket.NoSuchPacket.selector);
        rp.claimRedPacket(alice, "X", s);
        TapeTapeRedPacket.RedPacket memory p = rp.getRedPacketInfo(alice, "X");
        assertEq(p.creator, address(0));
        assertEq(p.totalCount, 0);
    }

    // Packet ids are namespaced per creator: another user using the same id does not collide.
    function test_idsArePerCreator() public {
        create("A", address(usdt), 1000e18, 1);
        usdt.mint(bob, 1e21);
        vm.startPrank(bob);
        usdt.approve(address(rp), type(uint256).max);
        rp.createRedPacket("A", address(usdt), 500e18, 1, claimKey, address(0), 5);
        vm.stopPrank();
        assertEq(rp.getRedPacketInfo(alice, "A").totalAmount, 995e18);
        assertEq(rp.getRedPacketInfo(bob, "A").totalAmount, 497.5e18);
    }

    function test_idCannotBeReusedEvenAfterRefund() public {
        create("A", address(usdt), 1000e18, 2);
        vm.warp(block.timestamp + 7 days);
        vm.prank(alice);
        rp.refundRedPacket("A", alice);
        vm.prank(alice);
        vm.expectRevert(TapeTapeRedPacket.PacketExists.selector);
        rp.createRedPacket("A", address(usdt), 1000e18, 2, claimKey, address(0), 5);
    }

    function test_createValidation() public {
        vm.startPrank(alice);
        vm.expectRevert(TapeTapeRedPacket.InvalidId.selector);
        rp.createRedPacket("", address(usdt), 100, 1, claimKey, address(0), 5);
        vm.expectRevert(TapeTapeRedPacket.InvalidId.selector);
        rp.createRedPacket("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", address(usdt), 100, 1, claimKey, address(0), 5);
        vm.expectRevert(TapeTapeRedPacket.InvalidClaimKey.selector);
        rp.createRedPacket("A", address(usdt), 100, 1, address(0), address(0), 5);
        vm.expectRevert(TapeTapeRedPacket.InvalidCount.selector);
        rp.createRedPacket("A", address(usdt), 100, 0, claimKey, address(0), 5);
        vm.expectRevert(TapeTapeRedPacket.InvalidCount.selector);
        rp.createRedPacket("A", address(usdt), 1e18, 1001, claimKey, address(0), 5);
        vm.expectRevert(TapeTapeRedPacket.FeeRateTooHigh.selector);
        rp.createRedPacket("A", address(usdt), 100, 1, claimKey, address(0), 4);
        vm.expectRevert(TapeTapeRedPacket.AmountTooSmall.selector);
        rp.createRedPacket("A", address(usdt), 3, 4, claimKey, address(0), 5);
        vm.expectRevert(TapeTapeRedPacket.IncorrectValue.selector);
        rp.createRedPacket{value: 1}("A", address(usdt), 100, 1, claimKey, address(0), 5);
        vm.expectRevert(TapeTapeRedPacket.IncorrectValue.selector);
        rp.createRedPacket{value: 99}("A", address(0), 100, 1, claimKey, address(0), 5);
        vm.expectRevert(TapeTapeRedPacket.UnsupportedToken.selector);
        rp.createRedPacket("A", makeAddr("eoa"), 100, 1, claimKey, address(0), 5);
        rp.createRedPacket("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", address(usdt), 2000, 1000, claimKey, address(0), 50);
        vm.stopPrank();
    }

    function test_feeLeavesAtLeastOnePerShare() public {
        // 1000 gross, fee 5 -> 995 net; 996 shares cannot each get 1.
        vm.prank(alice);
        vm.expectRevert(TapeTapeRedPacket.AmountTooSmall.selector);
        rp.createRedPacket("A", address(usdt), 1000, 996, claimKey, address(0), 5);
        create("B", address(usdt), 1000, 995);
    }

    function test_perTokenMinimum() public {
        vm.prank(owner);
        rp.setMinAmount(address(bem), 1e6); // 0.01 BEM
        vm.prank(alice);
        vm.expectRevert(TapeTapeRedPacket.AmountTooSmall.selector);
        rp.createRedPacket("A", address(bem), 1e6 - 1, 1, claimKey, address(0), 5);
        create("A", address(bem), 1e6, 1);
        // other tokens are unaffected
        create("B", address(usdt), 1, 1);
    }

    function test_feeOnTransferTokenRejected() public {
        FeeToken f = new FeeToken();
        f.mint(alice, 1e21);
        vm.startPrank(alice);
        f.approve(address(rp), type(uint256).max);
        vm.expectRevert(TapeTapeRedPacket.UnsupportedToken.selector);
        rp.createRedPacket("A", address(f), 1e20, 1, claimKey, address(0), 5);
        vm.stopPrank();
    }

    function test_refund() public {
        create("A", address(0), 10 ether, 4);
        claimAs(bob, "A");
        uint256 left = rp.getRedPacketInfo(alice, "A").remainAmount;
        vm.prank(alice);
        vm.expectRevert(TapeTapeRedPacket.RefundTooEarly.selector);
        rp.refundRedPacket("A", alice);
        vm.warp(block.timestamp + 7 days - 1);
        vm.prank(alice);
        vm.expectRevert(TapeTapeRedPacket.RefundTooEarly.selector);
        rp.refundRedPacket("A", alice);
        vm.warp(block.timestamp + 1);
        uint256 before = alice.balance;
        vm.prank(alice);
        assertEq(rp.refundRedPacket("A", alice), left);
        assertEq(alice.balance, before + left);
        TapeTapeRedPacket.RedPacket memory p = rp.getRedPacketInfo(alice, "A");
        assertTrue(p.refunded);
        assertEq(p.remainAmount, 0);
        assertEq(p.remainCount, 0);
        vm.prank(alice);
        vm.expectRevert(TapeTapeRedPacket.PacketRefunded.selector);
        rp.refundRedPacket("A", alice);
        bytes memory s = sign(keyPk, alice, "A", carol);
        vm.prank(carol);
        vm.expectRevert(TapeTapeRedPacket.PacketRefunded.selector);
        rp.claimRedPacket(alice, "A", s);
    }

    function test_onlyCreatorRefunds() public {
        create("A", address(usdt), 1000e18, 2);
        vm.warp(block.timestamp + 8 days);
        vm.prank(bob);
        vm.expectRevert(TapeTapeRedPacket.NoSuchPacket.selector);
        rp.refundRedPacket("A", bob);
    }

    function test_refundAfterFullyClaimedReverts() public {
        create("A", address(usdt), 1000e18, 1);
        claimAs(bob, "A");
        vm.warp(block.timestamp + 8 days);
        vm.prank(alice);
        vm.expectRevert(TapeTapeRedPacket.NothingToRefund.selector);
        rp.refundRedPacket("A", alice);
    }

    function test_claimStillPossibleAfterSevenDaysUntilRefund() public {
        create("A", address(usdt), 1000e18, 2);
        vm.warp(block.timestamp + 30 days);
        claimAs(bob, "A");
    }

    function test_reentrancyBlocked() public {
        create("A", address(0), 1 ether, 3);
        Reenterer r = new Reenterer(rp);
        bytes memory s = sign(keyPk, alice, "A", address(r));
        r.arm(alice, "A", s);
        r.claim();
        assertTrue(r.tried());
        assertFalse(r.reentered());
        assertEq(rp.getRedPacketInfo(alice, "A").remainCount, 2);
    }

    function test_receiverRejectingNativeOnlyHurtsItself() public {
        create("A", address(0), 1 ether, 2);
        Rejecter r = new Rejecter();
        bytes memory s = sign(keyPk, alice, "A", address(r));
        vm.expectRevert(TapeTapeRedPacket.TransferFailed.selector);
        r.claim(rp, alice, "A", s);
        claimAs(bob, "A");
    }

    function test_ownerPowers() public {
        create("A", address(usdt), 1000e18, 2);
        vm.prank(bob);
        vm.expectRevert(abi.encodeWithSelector(Ownable.OwnableUnauthorizedAccount.selector, bob));
        rp.setFeeRate(1);
        vm.prank(bob);
        vm.expectRevert(abi.encodeWithSelector(Ownable.OwnableUnauthorizedAccount.selector, bob));
        rp.withdrawFees(address(usdt), bob);
        vm.prank(bob);
        vm.expectRevert(abi.encodeWithSelector(Ownable.OwnableUnauthorizedAccount.selector, bob));
        rp.setMinAmount(address(usdt), 1);

        vm.startPrank(owner);
        vm.expectRevert(TapeTapeRedPacket.FeeRateTooHigh.selector);
        rp.setFeeRate(51);
        rp.setFeeRate(50);
        vm.expectRevert(TapeTapeRedPacket.InvalidRecipient.selector);
        rp.withdrawFees(address(usdt), address(0));
        assertEq(rp.withdrawFees(address(usdt), owner), 5e18);
        vm.expectRevert(TapeTapeRedPacket.NothingToWithdraw.selector);
        rp.withdrawFees(address(usdt), owner);
        vm.stopPrank();
        // escrow untouched
        assertEq(usdt.balanceOf(address(rp)), 995e18);
        // the creator's maxFeeRate guards against the raise
        vm.prank(alice);
        vm.expectRevert(TapeTapeRedPacket.FeeRateTooHigh.selector);
        rp.createRedPacket("B", address(usdt), 1000e18, 2, claimKey, address(0), 5);
    }

    function test_ownershipIsTwoStep() public {
        vm.prank(owner);
        rp.transferOwnership(bob);
        assertEq(rp.owner(), owner);
        vm.prank(bob);
        rp.acceptOwnership();
        assertEq(rp.owner(), bob);
    }

    function test_constructorFeeCap() public {
        vm.expectRevert(TapeTapeRedPacket.FeeRateTooHigh.selector);
        new TapeTapeRedPacket(owner, 51);
        vm.expectRevert(abi.encodeWithSelector(Ownable.OwnableInvalidOwner.selector, address(0)));
        new TapeTapeRedPacket(address(0), 0);
    }

    function test_maxCountRecords() public {
        create("A", address(usdt), 1e24, 1000);
        for (uint256 i = 0; i < 1000; i++) {
            claimAs(address(uint160(0x10000 + i)), "A");
        }
        TapeTapeRedPacket.RedPacket memory p = rp.getRedPacketInfo(alice, "A");
        assertEq(p.remainCount, 0);
        assertEq(p.remainAmount, 0);
        assertEq(rp.getClaimRecords(alice, "A").length, 1000);
    }

    function testFuzz_sharesSumToNetAndEachPositive(uint256 amount, uint8 countSeed, uint256 seed) public {
        uint256 count = bound(countSeed, 1, 60);
        amount = bound(amount, 2 * count, 1e30);
        create("F", address(usdt), amount, count);
        uint256 net = rp.getRedPacketInfo(alice, "F").totalAmount;
        uint256 sum;
        for (uint256 i = 0; i < count; i++) {
            vm.warp(block.timestamp + (seed >> i) % 100);
            vm.prevrandao(bytes32(seed ^ i));
            uint256 a = claimAs(address(uint160(0x20000 + i)), "F");
            assertGt(a, 0);
            sum += a;
        }
        assertEq(sum, net);
        assertEq(usdt.balanceOf(address(rp)), rp.collectedFees(address(usdt)));
    }
}

/// Invariant: the contract always holds at least everything it owes (remaining shares + fees).
contract Handler is Test {
    TapeTapeRedPacket public rp;
    Token public usdt;
    uint256 constant PK = 0xBEEF;
    address[] public creators;
    string[] public ids;
    uint256 public n;

    constructor(TapeTapeRedPacket r, Token t) {
        rp = r;
        usdt = t;
    }

    function _sig(address creator, string memory id, address claimer) internal view returns (bytes memory) {
        bytes32 d = MessageHashUtils.toEthSignedMessageHash(rp.claimDigest(creator, id, claimer));
        (uint8 v, bytes32 r, bytes32 s) = vm.sign(PK, d);
        return abi.encodePacked(r, s, v);
    }

    function create(uint256 who, uint256 amount, uint256 count, bool native) external {
        address c = address(uint160(0x5000 + (who % 5)));
        count = bound(count, 1, 20);
        amount = bound(amount, 2 * count, 1e24);
        string memory id = vm.toString(n++);
        address token = native ? address(0) : address(usdt);
        vm.deal(c, amount);
        usdt.mint(c, amount);
        vm.startPrank(c);
        usdt.approve(address(rp), amount);
        rp.createRedPacket{value: native ? amount : 0}(id, token, amount, count, vm.addr(PK), address(0), 50);
        vm.stopPrank();
        creators.push(c);
        ids.push(id);
    }

    function claim(uint256 i, uint256 who) external {
        if (ids.length == 0) return;
        i = i % ids.length;
        address claimer = address(uint160(0x9000 + (who % 30)));
        bytes memory s = _sig(creators[i], ids[i], claimer);
        vm.prank(claimer);
        rp.claimRedPacket(creators[i], ids[i], s);
    }

    function refund(uint256 i, uint256 dt) external {
        if (ids.length == 0) return;
        i = i % ids.length;
        vm.warp(block.timestamp + bound(dt, 0, 10 days));
        vm.prank(creators[i]);
        rp.refundRedPacket(ids[i], creators[i]);
    }

    function setFee(uint256 r) external {
        vm.prank(rp.owner());
        rp.setFeeRate(bound(r, 0, 50));
    }

    function withdraw(bool native) external {
        vm.prank(rp.owner());
        rp.withdrawFees(native ? address(0) : address(usdt), address(0xFEE));
    }

    function count() external view returns (uint256) {
        return ids.length;
    }

    function owed(bool native) external view returns (uint256 total) {
        address token = native ? address(0) : address(usdt);
        for (uint256 i = 0; i < ids.length; i++) {
            TapeTapeRedPacket.RedPacket memory p = rp.getRedPacketInfo(creators[i], ids[i]);
            if (p.token == token) total += p.remainAmount;
            // every share left can still be paid at least 1
            assert(p.remainAmount >= p.remainCount);
            assert(p.remainCount <= p.totalCount);
        }
        total += rp.collectedFees(token);
    }
}

contract TapeTapeRedPacketInvariant is Test {
    TapeTapeRedPacket rp;
    Token usdt;
    Handler h;

    function setUp() public {
        rp = new TapeTapeRedPacket(address(this), 5);
        usdt = new Token(18);
        h = new Handler(rp, usdt);
        targetContract(address(h));
    }

    function invariant_solvent() public view {
        assertGe(usdt.balanceOf(address(rp)), h.owed(false));
        assertGe(address(rp).balance, h.owed(true));
    }

    function invariant_exactlySolvent() public view {
        // nothing can be sent in except through createRedPacket, so balances match exactly
        assertEq(usdt.balanceOf(address(rp)), h.owed(false));
        assertEq(address(rp).balance, h.owed(true));
    }
}
