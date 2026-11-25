// SPDX-License-Identifier: MIT
pragma solidity 0.8.28;

import {console} from "forge-std/Test.sol";
import {Base, Token} from "../TapeTapeRedPacket.t.sol";
import {TapeTapeRedPacket} from "../../src/TapeTapeRedPacket.sol";

/// 第三轮审计的概念验证。
contract Audit3Test is Base {
    string constant UUID = "8F14E45F-CEEA-467A-9F0B-7C1B2D3E4F50";

    // 1) 转账：签名不校验、只认 recipient；非 recipient 拿着有效领取签名也领不了；创建者 7 天内撤不回。
    function test_transferIgnoresSignatureOnlyRecipient() public {
        vm.prank(alice);
        rp.createRedPacket{value: 1 ether}(UUID, address(0), 1 ether, 1, claimKey, bob, 5);
        // carol 有领取密钥签的、绑定 carol 的合法签名，也领不了
        bytes memory s = sign(keyPk, alice, UUID, carol);
        vm.prank(carol);
        vm.expectRevert(TapeTapeRedPacket.NotRecipient.selector);
        rp.claimRedPacket(alice, UUID, s);
        // 创建者自己也领不了
        bytes memory sa = sign(keyPk, alice, UUID, alice);
        vm.prank(alice);
        vm.expectRevert(TapeTapeRedPacket.NotRecipient.selector);
        rp.claimRedPacket(alice, UUID, sa);
        vm.prank(alice);
        vm.expectRevert(TapeTapeRedPacket.RefundTooEarly.selector);
        rp.refundRedPacket(UUID, alice);
        // bob 带垃圾签名也能领
        uint256 before = bob.balance;
        vm.prank(bob);
        uint256 got = rp.claimRedPacket(alice, UUID, hex"deadbeef");
        assertEq(got, 1 ether - 1 ether * 5 / 1000);
        assertEq(bob.balance - before, got);
    }

    // 2) ID 字符集：边界逐个验证
    function test_idCharset() public {
        string[9] memory bad = ["", "a b", "a_b", unicode"红包", "a\x00", "a.b", "a/b", "a+b", string(hex"61e280ae62")];
        for (uint256 i = 0; i < bad.length; i++) {
            vm.prank(alice);
            vm.expectRevert(TapeTapeRedPacket.InvalidId.selector);
            rp.createRedPacket{value: 1 ether}(bad[i], address(0), 1 ether, 1, claimKey, address(0), 5);
        }
        bytes memory s64 = new bytes(64);
        for (uint256 i = 0; i < 64; i++) s64[i] = "Z";
        vm.prank(alice);
        rp.createRedPacket{value: 1 ether}(string(s64), address(0), 1 ether, 1, claimKey, address(0), 5);
        bytes memory s65 = abi.encodePacked(s64, "0");
        vm.prank(alice);
        vm.expectRevert(TapeTapeRedPacket.InvalidId.selector);
        rp.createRedPacket{value: 1 ether}(string(s65), address(0), 1 ether, 1, claimKey, address(0), 5);
        // 边界字符 0 9 A Z a z -
        vm.prank(alice);
        rp.createRedPacket{value: 1 ether}("09AZaz-", address(0), 1 ether, 1, claimKey, address(0), 5);
        // 区分大小写：同一创建者 abc / ABC 是两个红包
        vm.prank(alice);
        rp.createRedPacket{value: 1 ether}("abc", address(0), 1 ether, 1, claimKey, address(0), 5);
        vm.prank(alice);
        rp.createRedPacket{value: 1 ether}("ABC", address(0), 1 ether, 1, claimKey, address(0), 5);
    }

    // 3) 随机金额：金额接近 2^256 也不溢出，多份领完总和等于净额
    function test_hugeAmountNoOverflow() public {
        Token big = new Token(18);
        uint256 amt = type(uint256).max / 50; // fee 乘法上限内的最大值
        big.mint(alice, amt);
        vm.prank(alice);
        big.approve(address(rp), amt);
        vm.prank(alice);
        rp.createRedPacket(UUID, address(big), amt, 7, claimKey, address(0), 5);
        uint256 sum;
        for (uint256 i = 0; i < 7; i++) {
            address w = address(uint160(0x1000 + i));
            vm.warp(block.timestamp + 1);
            sum += claimAs(w, UUID);
        }
        assertEq(sum, amt - amt * 5 / 1000);
        assertEq(big.balanceOf(address(rp)), amt * 5 / 1000);
    }

    // 4) 最小份额：net == count 时每份恰好 1
    function test_exactlyOnePerShare() public {
        vm.prank(alice);
        rp.createRedPacket{value: 1000}(UUID, address(0), 1000, 995, claimKey, address(0), 5); // fee 5 → net 995
        for (uint256 i = 0; i < 995; i++) {
            assertEq(claimAs(address(uint160(0x5000 + i)), UUID), 1);
        }
    }

    // 5) renounce 禁用；非主人调用先报未授权
    function test_renounceDisabled() public {
        vm.prank(owner);
        vm.expectRevert(TapeTapeRedPacket.RenounceDisabled.selector);
        rp.renounceOwnership();
        vm.prank(bob);
        vm.expectRevert();
        rp.renounceOwnership();
        assertEq(rp.owner(), owner);
    }

    // 6) 客户端 maxFeeRate 的作用范围：费率涨了之后用旧值创建会被拒绝（合约侧正确）；
    //    客户端每次都用「当场读到的」费率当 maxFeeRate，所以这条保护在客户端上等于没有（见报告）。
    function test_feeRaceProtectionOnlyIfClientPinsRate() public {
        vm.prank(owner);
        rp.setFeeRate(50);
        vm.prank(alice);
        vm.expectRevert(TapeTapeRedPacket.FeeRateTooHigh.selector);
        rp.createRedPacket{value: 1 ether}(UUID, address(0), 1 ether, 1, claimKey, address(0), 5);
        // 客户端行为：maxFeeRate = 当场 feeRate() → 照样通过，用户被扣 5%
        uint256 rate = rp.feeRate();
        vm.prank(alice);
        rp.createRedPacket{value: 1 ether}(UUID, address(0), 1 ether, 1, claimKey, address(0), rate);
        assertEq(rp.getRedPacketInfo(alice, UUID).fee, 0.05 ether);
    }

    // 7) gas 实测：客户端 CREATE_GAS = 420_000（仅在需要先 approve、无法预估时用来报价）
    function test_gasNumbers() public {
        Token t = new Token(18);
        t.mint(alice, 1e30);
        vm.prank(alice);
        t.approve(address(rp), type(uint256).max);
        // 冷启动：这个代币第一次进合约（collectedFees 槽从 0 写成非 0）
        vm.prank(alice);
        uint256 g0 = gasleft();
        rp.createRedPacket(UUID, address(t), 1e18, 1000, claimKey, bob == address(0) ? address(0) : address(0), 5);
        uint256 createErc20 = g0 - gasleft();
        vm.prank(alice);
        g0 = gasleft();
        rp.createRedPacket{value: 1 ether}("N-1", address(0), 1 ether, 1, claimKey, bob, 5);
        uint256 createNative = g0 - gasleft();
        bytes memory s = sign(keyPk, alice, UUID, carol);
        vm.prank(carol);
        g0 = gasleft();
        rp.claimRedPacket(alice, UUID, s);
        uint256 claimGas = g0 - gasleft();
        console.log("create erc20 (in-call gas, excl. 21k+calldata)", createErc20);
        console.log("create native transfer", createNative);
        console.log("claim erc20", claimGas);
        assertLt(createErc20 + 21000 + 16 * 400, 420_000);
    }
}
