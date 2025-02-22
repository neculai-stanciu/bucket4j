/*-
 * ========================LICENSE_START=================================
 * Bucket4j
 * %%
 * Copyright (C) 2015 - 2022 Vladimir Bukhtoyarov
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * =========================LICENSE_END==================================
 */

package io.github.bucket4j.redis.jedis.cas;

import io.github.bucket4j.TimeMeter;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.generic.compare_and_swap.AbstractCompareAndSwapBasedProxyManager;
import io.github.bucket4j.distributed.proxy.generic.compare_and_swap.AsyncCompareAndSwapOperation;
import io.github.bucket4j.distributed.proxy.generic.compare_and_swap.CompareAndSwapOperation;
import io.github.bucket4j.distributed.remote.RemoteBucketState;
import io.github.bucket4j.redis.AbstractRedisProxyManagerBuilder;
import io.github.bucket4j.redis.consts.LuaScripts;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisCluster;
import redis.clients.jedis.util.Pool;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class JedisBasedProxyManager extends AbstractCompareAndSwapBasedProxyManager<byte[]> {

    private final RedisApi redisApi;
    private final ExpirationAfterWriteStrategy expirationStrategy;

    public static JedisBasedProxyManagerBuilder builderFor(Pool<Jedis> jedisPool) {
        Objects.requireNonNull(jedisPool);
        RedisApi redisApi = new RedisApi() {
            @Override
            public Object eval(byte[] script, int keyCount, byte[]... params) {
                try (Jedis jedis = jedisPool.getResource()) {
                    return jedis.eval(script, 1, params);
                }
            }
            @Override
            public byte[] get(byte[] key) {
                try (Jedis jedis = jedisPool.getResource()) {
                    return jedis.get(key);
                }
            }
            @Override
            public void delete(byte[] key) {
                try (Jedis jedis = jedisPool.getResource()) {
                    jedis.del(key);
                }
            }
        };
        return new JedisBasedProxyManagerBuilder(redisApi);
    }

    public static JedisBasedProxyManagerBuilder builderFor(JedisCluster jedisCluster) {
        Objects.requireNonNull(jedisCluster);
        RedisApi redisApi = new RedisApi() {
            @Override
            public Object eval(byte[] script, int keyCount, byte[]... params) {
                return jedisCluster.eval(script, keyCount, params);
            }
            @Override
            public byte[] get(byte[] key) {
                return jedisCluster.get(key);
            }
            @Override
            public void delete(byte[] key) {
                jedisCluster.del(key);
            }
        };
        return new JedisBasedProxyManagerBuilder(redisApi);
    }

    public static class JedisBasedProxyManagerBuilder extends AbstractRedisProxyManagerBuilder<JedisBasedProxyManagerBuilder> {

        private final RedisApi redisApi;

        private JedisBasedProxyManagerBuilder(RedisApi redisApi) {
            this.redisApi = Objects.requireNonNull(redisApi);
        }

        public JedisBasedProxyManager build() {
            return new JedisBasedProxyManager(this);
        }

    }

    private JedisBasedProxyManager(JedisBasedProxyManagerBuilder builder) {
        super(builder.getClientSideConfig());
        this.redisApi = builder.redisApi;
        this.expirationStrategy = builder.getNotNullExpirationStrategy();
    }

    @Override
    protected CompareAndSwapOperation beginCompareAndSwapOperation(byte[] key) {
        return new CompareAndSwapOperation() {
            @Override
            public Optional<byte[]> getStateData() {
                return Optional.ofNullable(redisApi.get(key));
            }

            @Override
            public boolean compareAndSwap(byte[] originalData, byte[] newData, RemoteBucketState newState) {
                return JedisBasedProxyManager.this.compareAndSwap(key, originalData, newData, newState);
            }
        };
    }

    @Override
    protected AsyncCompareAndSwapOperation beginAsyncCompareAndSwapOperation(byte[] key) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void removeProxy(byte[] key) {
        redisApi.delete(key);
    }

    @Override
    protected CompletableFuture<Void> removeAsync(byte[] key) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isAsyncModeSupported() {
        return false;
    }

    private Boolean compareAndSwap(byte[] key, byte[] originalData, byte[] newData, RemoteBucketState newState) {
        long ttlMillis = calculateTtlMillis(newState);
        if (ttlMillis > 0) {
            if (originalData == null) {
                // nulls are prohibited as values, so "replace" must not be used in such cases
                byte[][] keysAndArgs = {key, newData, encodeLong(ttlMillis)};
                Object res = redisApi.eval(LuaScripts.SCRIPT_SET_NX_PX.getBytes(StandardCharsets.UTF_8), 1, keysAndArgs);
                return res != null && !res.equals(0L);
            } else {
                byte[][] keysAndArgs = {key, originalData, newData, encodeLong(ttlMillis)};
                Object res = redisApi.eval(LuaScripts.SCRIPT_COMPARE_AND_SWAP_PX.getBytes(StandardCharsets.UTF_8), 1, keysAndArgs);
                return res != null && !res.equals(0L);
            }
        } else {
            if (originalData == null) {
                // nulls are prohibited as values, so "replace" must not be used in such cases
                byte[][] keysAndArgs = {key, newData};
                Object res = redisApi.eval(LuaScripts.SCRIPT_SET_NX.getBytes(StandardCharsets.UTF_8), 1, keysAndArgs);
                return res != null && !res.equals(0L);
            } else {
                byte[][] keysAndArgs = {key, originalData, newData};
                Object res = redisApi.eval(LuaScripts.SCRIPT_COMPARE_AND_SWAP.getBytes(StandardCharsets.UTF_8), 1, keysAndArgs);
                return res != null && !res.equals(0L);
            }
        }
    }

    private byte[] encodeLong(Long value) {
        return ("" + value).getBytes(StandardCharsets.UTF_8);
    }

    private long calculateTtlMillis(RemoteBucketState state) {
        Optional<TimeMeter> clock = getClientSideConfig().getClientSideClock();
        long currentTimeNanos = clock.isPresent() ? clock.get().currentTimeNanos() : System.currentTimeMillis() * 1_000_000;
        return expirationStrategy.calculateTimeToLiveMillis(state, currentTimeNanos);
    }

    private interface RedisApi {

        Object eval(final byte[] script, final int keyCount, final byte[]... params);

        byte[] get(byte[] key);

        void delete(byte[] key);

    }

}