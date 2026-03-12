package com.web3.txsentry.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {

    @Value("${spring.data.redis.host:127.0.0.1}")
    private String host;

    @Value("${spring.data.redis.port:6379}")
    private String port;

    @Value("${spring.data.redis.password:}")
    private String password;

    /**
     * Initialize RedissonClient with explicit connection pool settings.
     * The destroyMethod ensures graceful shutdown of Netty threads when the app stops.
     */
    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();
        String address = "redis://" + host + ":" + port;

        config.useSingleServer()
                .setAddress(address)
                // Minimum number of idle connections in the pool
                .setConnectionMinimumIdleSize(10)
                // Maximum size of the connection pool
                .setConnectionPoolSize(50)
                // Time to wait for a connection from the pool (milliseconds)
                .setConnectTimeout(10000)
                // Redis server response timeout (milliseconds)
                .setTimeout(3000);

        if (password != null && !password.isEmpty()) {
            config.useSingleServer().setPassword(password);
        }

        // Initialize and return the RedissonClient bean
        return Redisson.create(config);
    }
}