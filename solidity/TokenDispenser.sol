// SPDX-License-Identifier: MIT
pragma solidity ^0.8.19;

// 极简接口，只声明我们需要用到的函数
interface IERC20 {
    function transferFrom(address sender, address recipient, uint256 amount) external returns (bool);
}

/**
 * @title TokenDispenser
 * @dev 极致 Gas 优化的批量代币分发器
 */
contract TokenDispenser {

    /**
     * @notice 批量转账代币
     * @param token ERC-20 代币的合约地址 (比如 USDT 地址)
     * @param recipients 收款人地址数组
     * @param amounts 对应的金额数组
     * * 架构级优化：强行使用 `calldata` 而不是 `memory`，
     * 避免了将巨型数组从交易负载复制到内存的昂贵 Gas 消耗。
     */
    function batchTransferToken(
        address token,
        address[] calldata recipients,
        uint256[] calldata amounts
    ) external {
        // 1. 防御性编程：数量必须绝对对齐
        require(recipients.length == amounts.length, "Dispenser: length mismatch");

        IERC20 erc20token = IERC20(token);

        // 2. 循环切割，底层划转
        for (uint256 i = 0; i < recipients.length; i++) {
            // 核心逻辑：从调用者（你的网关热钱包）直接把钱划走，塞给用户
            // 注意：这要求你的热钱包，必须提前向这个 Dispenser 合约执行过一次无上限的 approve (授权)
            require(
                erc20token.transferFrom(msg.sender, recipients[i], amounts[i]),
                "Dispenser: transfer failed"
            );
        }
    }
}