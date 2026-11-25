// SPDX-License-Identifier: MIT
pragma solidity 0.8.28;

import {IERC20} from "@openzeppelin/contracts/token/ERC20/IERC20.sol";
import {SafeERC20} from "@openzeppelin/contracts/token/ERC20/utils/SafeERC20.sol";
import {ECDSA} from "@openzeppelin/contracts/utils/cryptography/ECDSA.sol";
import {MessageHashUtils} from "@openzeppelin/contracts/utils/cryptography/MessageHashUtils.sol";
import {Ownable} from "@openzeppelin/contracts/access/Ownable.sol";
import {Ownable2Step} from "@openzeppelin/contracts/access/Ownable2Step.sol";
import {ReentrancyGuard} from "@openzeppelin/contracts/utils/ReentrancyGuard.sol";

/// @title TapeTape red packets and transfers
/// @notice A red packet (or a transfer, which is a red packet with count 1) is escrowed here until
/// its shares are claimed or the creator refunds the rest after 7 days.
///
/// Claiming needs a one-time "claim key" that the creator generates per packet. Its address is stored
/// on creation; its private key travels inside the end-to-end encrypted chat message, so only chat
/// members can claim. A claim signature commits to the chain, this contract, the packet and the
/// claimer address, and is checked against msg.sender, so a signature seen on chain or in the mempool
/// cannot be reused by anyone else.
///
/// Packets are namespaced by creator: the storage key is keccak256(creator, id), so nobody can occupy
/// someone else's packet id.
///
/// The contract is not upgradeable and the owner cannot move escrowed funds. The owner can only tune the
/// fee rate (capped at MAX_FEE_RATE, and each creator caps it again per call with maxFeeRate), set
/// per-token minimum amounts and withdraw collected fees.
contract TapeTapeRedPacket is Ownable2Step, ReentrancyGuard {
    using SafeERC20 for IERC20;

    uint256 public constant FEE_DENOMINATOR = 1000;
    uint256 public constant MAX_FEE_RATE = 50; // 5%
    uint256 public constant MAX_COUNT = 1000;
    uint256 public constant MAX_ID_LENGTH = 64;
    uint256 public constant REFUND_DELAY = 7 days;
    bytes32 public constant CLAIM_TAG = keccak256("TapeTapeRedPacket.claim.v1");

    struct RedPacket {
        address creator;
        address token; // address(0) = native coin
        address claimKey;
        address recipient; // non-zero: only this address may claim (transfers)
        uint256 totalAmount; // after fee
        uint256 remainAmount;
        uint256 fee;
        uint256 totalCount;
        uint256 remainCount;
        uint256 createTime;
        bool refunded;
    }

    struct ClaimRecord {
        address claimer;
        uint256 amount;
    }

    mapping(bytes32 => RedPacket) private _packets;
    mapping(bytes32 => mapping(address => uint256)) private _claimed;
    mapping(bytes32 => address[]) private _claimers;

    /// @notice Fee in per mille, taken from the amount on creation.
    uint256 public feeRate;
    /// @notice Minimum gross amount per token (address(0) = native coin).
    mapping(address => uint256) public minAmount;
    /// @notice Fees collected per token and not yet withdrawn.
    mapping(address => uint256) public collectedFees;

    event RedPacketCreated(
        bytes32 indexed key,
        address indexed creator,
        string id,
        address token,
        uint256 amount,
        uint256 fee,
        uint256 count,
        address claimKey,
        address recipient
    );
    event RedPacketClaimed(bytes32 indexed key, address indexed claimer, uint256 amount);
    event RedPacketRefunded(bytes32 indexed key, address indexed creator, address to, uint256 amount);
    event FeeRateUpdated(uint256 oldRate, uint256 newRate);
    event MinAmountUpdated(address indexed token, uint256 oldAmount, uint256 newAmount);
    event FeesWithdrawn(address indexed token, address indexed to, uint256 amount);

    error InvalidId();
    error InvalidClaimKey();
    error InvalidCount();
    error FeeRateTooHigh();
    error PacketExists();
    error AmountTooSmall();
    error IncorrectValue();
    error UnsupportedToken();
    error NoSuchPacket();
    error PacketRefunded();
    error NoSharesLeft();
    error AlreadyClaimed();
    error InvalidSignature();
    error RefundTooEarly();
    error NothingToRefund();
    error InvalidRecipient();
    error NothingToWithdraw();
    error TransferFailed();
    error NotRecipient();
    error RenounceDisabled();

    constructor(address initialOwner, uint256 initialFeeRate) Ownable(initialOwner) {
        if (initialFeeRate > MAX_FEE_RATE) revert FeeRateTooHigh();
        feeRate = initialFeeRate;
        emit FeeRateUpdated(0, initialFeeRate);
    }

    // ---------------------------------------------------------------------------------------------
    // Users

    /// @param id Packet id chosen by the creator (a UUID), 1 to 64 bytes, unique per creator forever.
    /// @param token ERC-20 token, or address(0) for the native coin (then msg.value must equal amount).
    /// @param amount Gross amount; the fee is taken from it.
    /// @param count Number of shares, 1 to MAX_COUNT.
    /// @param claimKey Address of the one-time claim key.
    /// @param recipient Zero for a red packet anyone with the claim key may claim. Non-zero (only with count 1) for a
    /// transfer that only this address may claim; the creator cannot take it back before the refund delay.
    /// @param maxFeeRate The highest fee rate the creator accepts; protects against a fee change racing the call.
    function createRedPacket(
        string calldata id,
        address token,
        uint256 amount,
        uint256 count,
        address claimKey,
        address recipient,
        uint256 maxFeeRate
    ) external payable nonReentrant returns (bytes32 key) {
        _checkId(id);
        if (claimKey == address(0)) revert InvalidClaimKey();
        if (count == 0 || count > MAX_COUNT) revert InvalidCount();
        if (recipient != address(0) && count != 1) revert InvalidCount();
        uint256 rate = feeRate;
        if (rate > maxFeeRate) revert FeeRateTooHigh();

        key = packetKey(msg.sender, id);
        if (_packets[key].creator != address(0)) revert PacketExists();

        if (amount < minAmount[token]) revert AmountTooSmall();
        uint256 fee = (amount * rate) / FEE_DENOMINATOR;
        uint256 net = amount - fee;
        if (net < count) revert AmountTooSmall();

        if (token == address(0)) {
            if (msg.value != amount) revert IncorrectValue();
        } else {
            if (msg.value != 0) revert IncorrectValue();
            if (token.code.length == 0) revert UnsupportedToken();
            uint256 before = IERC20(token).balanceOf(address(this));
            IERC20(token).safeTransferFrom(msg.sender, address(this), amount);
            // Fee-on-transfer and rebasing tokens would break the accounting.
            if (IERC20(token).balanceOf(address(this)) - before != amount) revert UnsupportedToken();
        }

        _packets[key] = RedPacket({
            creator: msg.sender,
            token: token,
            claimKey: claimKey,
            recipient: recipient,
            totalAmount: net,
            remainAmount: net,
            fee: fee,
            totalCount: count,
            remainCount: count,
            createTime: block.timestamp,
            refunded: false
        });
        collectedFees[token] += fee;

        emit RedPacketCreated(key, msg.sender, id, token, net, fee, count, claimKey, recipient);
    }

    /// @param creator The packet creator.
    /// @param id The packet id.
    /// @param signature 65-byte EIP-191 signature by the claim key over claimDigest(creator, id, msg.sender).
    /// Ignored for a transfer (recipient set): only the recipient can claim it.
    function claimRedPacket(address creator, string calldata id, bytes calldata signature)
        external
        nonReentrant
        returns (uint256 amount)
    {
        bytes32 key = packetKey(creator, id);
        RedPacket storage p = _packets[key];
        if (p.creator == address(0)) revert NoSuchPacket();
        if (p.refunded) revert PacketRefunded();
        if (p.remainCount == 0) revert NoSharesLeft();
        if (_claimed[key][msg.sender] != 0) revert AlreadyClaimed();
        if (p.recipient != address(0)) {
            // A transfer is bound to its recipient; no claim-key signature needed, so a recipient who lost the
            // chat message can still claim.
            if (msg.sender != p.recipient) revert NotRecipient();
        } else {
            bytes32 digest = MessageHashUtils.toEthSignedMessageHash(claimDigest(creator, id, msg.sender));
            (address signer, ECDSA.RecoverError err,) = ECDSA.tryRecover(digest, signature);
            if (err != ECDSA.RecoverError.NoError || signer != p.claimKey) revert InvalidSignature();
        }

        uint256 remainCount = p.remainCount;
        uint256 remainAmount = p.remainAmount;
        if (remainCount == 1) {
            amount = remainAmount;
        } else {
            // Up to twice the average share, at least 1, leaving at least 1 for every other share.
            // remainAmount >= remainCount always holds, so maxAmount >= 2. Divide first so it cannot overflow.
            uint256 maxAmount = (remainAmount / remainCount) * 2;
            uint256 rand = uint256(
                keccak256(abi.encode(block.timestamp, block.prevrandao, msg.sender, remainCount, key))
            );
            amount = 1 + (rand % maxAmount);
            uint256 cap = remainAmount - (remainCount - 1);
            if (amount > cap) amount = cap;
        }

        p.remainAmount = remainAmount - amount;
        p.remainCount = remainCount - 1;
        _claimed[key][msg.sender] = amount;
        _claimers[key].push(msg.sender);

        _send(p.token, msg.sender, amount);
        emit RedPacketClaimed(key, msg.sender, amount);
    }

    /// @notice The creator takes back what is left, no earlier than REFUND_DELAY after creation.
    /// @param to Where to send it (normally the creator; lets a creator blacklisted by the token still recover funds).
    function refundRedPacket(string calldata id, address to) external nonReentrant returns (uint256 amount) {
        if (to == address(0)) revert InvalidRecipient();
        bytes32 key = packetKey(msg.sender, id);
        RedPacket storage p = _packets[key];
        if (p.creator == address(0)) revert NoSuchPacket();
        if (p.refunded) revert PacketRefunded();
        if (block.timestamp < p.createTime + REFUND_DELAY) revert RefundTooEarly();
        amount = p.remainAmount;
        if (amount == 0) revert NothingToRefund();

        p.refunded = true;
        p.remainAmount = 0;
        p.remainCount = 0;

        _send(p.token, to, amount);
        emit RedPacketRefunded(key, msg.sender, to, amount);
    }

    // ---------------------------------------------------------------------------------------------
    // Views

    function packetKey(address creator, string calldata id) public pure returns (bytes32) {
        return keccak256(abi.encode(creator, id));
    }

    /// @notice The 32-byte message the claim key signs (with the EIP-191 "\x19Ethereum Signed Message:\n32" prefix).
    function claimDigest(address creator, string calldata id, address claimer) public view returns (bytes32) {
        return keccak256(abi.encode(CLAIM_TAG, block.chainid, address(this), creator, keccak256(bytes(id)), claimer));
    }

    /// @notice All fields are zero for an unknown packet.
    function getRedPacketInfo(address creator, string calldata id) external view returns (RedPacket memory) {
        return _packets[packetKey(creator, id)];
    }

    function getClaimedAmount(address creator, string calldata id, address user) external view returns (uint256) {
        return _claimed[packetKey(creator, id)][user];
    }

    function hasClaimed(address creator, string calldata id, address user) external view returns (bool) {
        return _claimed[packetKey(creator, id)][user] != 0;
    }

    /// @notice At most MAX_COUNT records.
    function getClaimRecords(address creator, string calldata id) external view returns (ClaimRecord[] memory records) {
        bytes32 key = packetKey(creator, id);
        address[] storage list = _claimers[key];
        records = new ClaimRecord[](list.length);
        for (uint256 i = 0; i < list.length; i++) {
            records[i] = ClaimRecord({claimer: list[i], amount: _claimed[key][list[i]]});
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Owner

    function setFeeRate(uint256 newRate) external onlyOwner {
        if (newRate > MAX_FEE_RATE) revert FeeRateTooHigh();
        emit FeeRateUpdated(feeRate, newRate);
        feeRate = newRate;
    }

    function setMinAmount(address token, uint256 amount) external onlyOwner {
        emit MinAmountUpdated(token, minAmount[token], amount);
        minAmount[token] = amount;
    }

    /// @notice Disabled: without an owner the collected fees could never be withdrawn.
    function renounceOwnership() public view override onlyOwner {
        revert RenounceDisabled();
    }

    function withdrawFees(address token, address to) external onlyOwner nonReentrant returns (uint256 amount) {
        if (to == address(0)) revert InvalidRecipient();
        amount = collectedFees[token];
        if (amount == 0) revert NothingToWithdraw();
        collectedFees[token] = 0;
        _send(token, to, amount);
        emit FeesWithdrawn(token, to, amount);
    }

    // ---------------------------------------------------------------------------------------------

    /// Packet ids are restricted to [0-9A-Za-z-], 1 to MAX_ID_LENGTH bytes (UUIDs), so no control or
    /// direction-override characters reach clients through events.
    function _checkId(string calldata id) private pure {
        bytes calldata b = bytes(id);
        if (b.length == 0 || b.length > MAX_ID_LENGTH) revert InvalidId();
        for (uint256 i = 0; i < b.length; i++) {
            bytes1 c = b[i];
            if (!((c >= "0" && c <= "9") || (c >= "A" && c <= "Z") || (c >= "a" && c <= "z") || c == "-")) {
                revert InvalidId();
            }
        }
    }

    function _send(address token, address to, uint256 amount) private {
        if (token == address(0)) {
            (bool ok,) = to.call{value: amount}("");
            if (!ok) revert TransferFailed();
        } else {
            IERC20(token).safeTransfer(to, amount);
        }
    }
}
