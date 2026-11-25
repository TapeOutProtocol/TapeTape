// SPDX-License-Identifier: MIT
pragma solidity 0.8.28;

import {Test, console2} from "forge-std/Test.sol";
import {IERC20} from "@openzeppelin/contracts/token/ERC20/IERC20.sol";
import {MessageHashUtils} from "@openzeppelin/contracts/utils/cryptography/MessageHashUtils.sol";
import {TapeTapeRedPacket} from "../../src/TapeTapeRedPacket.sol";

/// Runs only when AUDIT_FORK_RPC is set, e.g.
/// AUDIT_FORK_RPC=https://rpc.xlayer.tech AUDIT_TOKEN=0x60e62efa9405d6873c5deabd4e6cc91c25363952 forge test --match-contract ForkTokens -vv
contract ForkTokens is Test {
    function test_configuredTokenWorksEndToEnd() public {
        string memory rpc = vm.envOr("AUDIT_FORK_RPC", string(""));
        if (bytes(rpc).length == 0) return;
        vm.createSelectFork(rpc);
        address token = vm.envAddress("AUDIT_TOKEN");
        address holder = vm.envOr("AUDIT_HOLDER", address(0));
        address alice = makeAddr("alice");
        uint256 amount = vm.envOr("AUDIT_AMOUNT", uint256(1e18));
        TapeTapeRedPacket rp = new TapeTapeRedPacket(address(this), 5);
        if (holder == address(0)) {
            deal(token, alice, amount);
        } else {
            vm.prank(holder);
            IERC20(token).transfer(alice, amount);
        }
        uint256 k = 0xBEEF;
        vm.prank(alice);
        IERC20(token).approve(address(rp), amount);
        vm.prank(alice);
        rp.createRedPacket("fork", token, amount, 3, vm.addr(k), address(0), 5);
        address bob = makeAddr("bob");
        bytes32 d = MessageHashUtils.toEthSignedMessageHash(rp.claimDigest(alice, "fork", bob));
        (uint8 v, bytes32 r, bytes32 s) = vm.sign(k, d);
        uint256 before = IERC20(token).balanceOf(bob);
        vm.prank(bob);
        uint256 got = rp.claimRedPacket(alice, "fork", abi.encodePacked(r, s, v));
        assertEq(IERC20(token).balanceOf(bob) - before, got, "claimer receives exactly the share");
        console2.log("chain", block.chainid, "claimed", got);
    }
}
