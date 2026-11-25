// SPDX-License-Identifier: MIT
pragma solidity 0.8.28;

import {Script, console2} from "forge-std/Script.sol";
import {MessageHashUtils} from "@openzeppelin/contracts/utils/cryptography/MessageHashUtils.sol";
import {TapeTapeRedPacket} from "../../src/TapeTapeRedPacket.sol";
import {AToken} from "./SpecAudit.t.sol";

/// Audit helper: real transactions on a local anvil (paris) to measure receipt gas.
/// anvil -a 1005 --hardfork paris --port 8549
/// forge script test/audit/GasOnAnvil.s.sol --rpc-url http://127.0.0.1:8549 --broadcast --slow
contract GasOnAnvil is Script {
    string constant MN = "test test test test test test test test test test test junk";
    string constant ID = "8F14E45F-CEEA-467A-9F0B-7C1B2D3E4F508F14E45F-CEEA-467A-9F0B-7C1B";

    function run() external {
        uint256 pk0 = vm.deriveKey(MN, 0);
        address a0 = vm.addr(pk0);
        uint256 claimPk = 0xC1A1;
        vm.startBroadcast(pk0);
        TapeTapeRedPacket rp = new TapeTapeRedPacket(a0, 5);
        AToken t = new AToken();
        t.mint(a0, 1e30);
        t.approve(address(rp), type(uint256).max);
        rp.createRedPacket(ID, address(t), 1e24, 1000, vm.addr(claimPk), address(0), 5);
        vm.stopBroadcast();
        for (uint256 i = 1; i <= 1000; i++) {
            uint256 pk = vm.deriveKey(MN, uint32(i));
            address who = vm.addr(pk);
            bytes32 d = MessageHashUtils.toEthSignedMessageHash(rp.claimDigest(a0, ID, who));
            (uint8 v, bytes32 r, bytes32 s) = vm.sign(claimPk, d);
            vm.broadcast(pk);
            rp.claimRedPacket(a0, ID, abi.encodePacked(r, s, v));
        }
        console2.log("rp", address(rp));
    }
}
