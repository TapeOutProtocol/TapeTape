// SPDX-License-Identifier: MIT
pragma solidity 0.8.28;

import {Test, console2} from "forge-std/Test.sol";
import {IERC20} from "@openzeppelin/contracts/token/ERC20/IERC20.sol";
import {IERC20Metadata} from "@openzeppelin/contracts/token/ERC20/extensions/IERC20Metadata.sol";
import {MessageHashUtils} from "@openzeppelin/contracts/utils/cryptography/MessageHashUtils.sol";
import {TapeTapeRedPacket} from "../../src/TapeTapeRedPacket.sol";

/// 审计第二轮：在 BSC / X Layer 主网分叉上，用部署页面配置的真实代币（地址、精度照抄 deploy/index.html）
/// 走完 创建 -> 领取 -> 7 天后退款 -> 提手续费，并核对每一步的实际到账。
/// 只在设置 AUDIT2_FORK=1 时运行：AUDIT2_FORK=1 forge test --match-contract ForkConfiguredTokens -vv
contract ForkConfiguredTokens is Test {
    uint256 constant KEY = 0xA11CE;

    function _on() internal view returns (bool) {
        return vm.envOr("AUDIT2_FORK", uint256(0)) == 1;
    }

    function _sig(TapeTapeRedPacket rp, address creator, string memory id, address claimer) internal view returns (bytes memory) {
        bytes32 d = MessageHashUtils.toEthSignedMessageHash(rp.claimDigest(creator, id, claimer));
        (uint8 v, bytes32 r, bytes32 s) = vm.sign(KEY, d);
        return abi.encodePacked(r, s, v);
    }

    /// 注意：forge 的 makeAddr("bob") 等常见测试地址在 BSC 主网上已被 EIP-7702 委托给清扫合约，收到原生币会被转走，
    /// 所以这里用不会撞上的地址，并确认没有代码。
    function _fresh(string memory tag) internal view returns (address a) {
        a = address(uint160(uint256(keccak256(abi.encode("tapetape-audit2", tag)))));
        require(a.code.length == 0, "fresh address has code");
    }

    function _bal(address token, address who) internal view returns (uint256) {
        return token == address(0) ? who.balance : IERC20(token).balanceOf(who);
    }

    function _run(address token, uint8 expectDecimals, uint256 amount) internal {
        TapeTapeRedPacket rp = new TapeTapeRedPacket(address(this), 50);
        if (token != address(0)) {
            assertEq(IERC20Metadata(token).decimals(), expectDecimals, "deploy page decimals");
        }
        address alice = _fresh(string.concat("alice", vm.toString(token)));
        if (token == address(0)) vm.deal(alice, amount);
        else deal(token, alice, amount);
        vm.startPrank(alice);
        if (token != address(0)) IERC20(token).approve(address(rp), amount);
        rp.createRedPacket{value: token == address(0) ? amount : 0}("fork-1", token, amount, 3, vm.addr(KEY), address(0), 50);
        vm.stopPrank();
        uint256 fee = amount * 50 / 1000;
        assertEq(_bal(token, address(rp)), amount, "escrow");
        address bob = _fresh("bob");
        uint256 b0 = _bal(token, bob);
        bytes memory s = _sig(rp, alice, "fork-1", bob);
        vm.prank(bob);
        uint256 got = rp.claimRedPacket(alice, "fork-1", s);
        assertEq(_bal(token, bob) - b0, got, "bob receives exactly his share");
        vm.warp(block.timestamp + 7 days);
        address back = _fresh("back");
        uint256 r0 = _bal(token, back);
        vm.prank(alice);
        uint256 refunded = rp.refundRedPacket("fork-1", back);
        assertEq(_bal(token, back) - r0, refunded, "refund received");
        assertEq(got + refunded + fee, amount, "conservation");
        assertEq(_bal(token, address(rp)), fee, "only fees left");
        _withdraw(rp, token, fee);
    }

    function _withdraw(TapeTapeRedPacket rp, address token, uint256 fee) internal {
        address feeTo = _fresh("feeTo");
        uint256 f0 = _bal(token, feeTo);
        rp.withdrawFees(token, feeTo);
        assertEq(_bal(token, feeTo) - f0, fee, "fees withdrawn");
        assertEq(_bal(token, address(rp)), 0, "empty");
        console2.log("chain", block.chainid, "token", token);
    }

    function test_bscTokens() public {
        if (!_on()) return;
        vm.createSelectFork(vm.envOr("AUDIT2_BSC_RPC", string("https://bsc-dataseed.bnbchain.org")));
        assertEq(block.chainid, 56);
        _run(address(0), 18, 1e18);
        _run(0x55d398326f99059fF775485246999027B3197955, 18, 100e18); // USDT
        _run(0x8AC76a51cc950d9822D68b83fE1Ad97B32Cd580d, 18, 100e18); // USDC
        _run(0x8d0D000Ee44948FC98c9B98A4FA4921476f08B0d, 18, 100e18); // USD1
    }

    function test_xlayerTokens() public {
        if (!_on()) return;
        vm.createSelectFork(vm.envOr("AUDIT2_XLAYER_RPC", string("https://rpc.xlayer.tech")));
        assertEq(block.chainid, 196);
        _run(address(0), 18, 1e18);
        _run(0x60e62Efa9405d6873C5deaBD4E6CC91c25363952, 8, 100_000e8); // BEM
    }
}
