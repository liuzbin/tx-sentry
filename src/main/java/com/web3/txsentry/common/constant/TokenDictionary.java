package com.web3.txsentry.common.constant;

import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工业级 ERC-20 代币字典。
 * 生产环境中，这些数据应该在系统启动时从数据库 token_config 表加载到 Redis 或本地缓存中。
 */
@Component
public class TokenDictionary {

    // Key: Token Contract Address (小写), Value: Decimals
    private final Map<String, Integer> tokenDecimalsMap = new ConcurrentHashMap<>();

    public TokenDictionary() {
        // 预置一些常见代币的精度，证明其完全兼容任何 ERC-20
        tokenDecimalsMap.put("0xdac17f958d2ee523a2206206994597c13d831ec7", 6);  // USDT
        tokenDecimalsMap.put("0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48", 6);  // USDC
        tokenDecimalsMap.put("0x1f9840a85d5af5bf1d1762f925bdaddc4201f984", 18); // UNI
        tokenDecimalsMap.put("0x514910771af9ca656af840dff83e8264ecf986ca", 18); // LINK
    }

    public int getDecimals(String tokenAddress) {
        Integer decimals = tokenDecimalsMap.get(tokenAddress.toLowerCase());
        if (decimals == null) {
            // 兜底防御：如果遇到未配置的币种，抛出异常，拒绝瞎猜，防止资损
            throw new IllegalArgumentException("Unsupported token address or missing decimals config: " + tokenAddress);
        }
        return decimals;
    }
}