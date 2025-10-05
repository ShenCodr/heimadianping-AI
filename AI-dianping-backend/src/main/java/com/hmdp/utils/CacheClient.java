package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.google.common.hash.BloomFilter;
import com.hmdp.dto.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * <p>
 * Redis工具类
 * </p>
 *
 * @author 超大王
 * @since 2025-09-19
 */
@Slf4j
@Component
public class CacheClient {

    // 布隆过滤器
    @Resource
    private BloomFilter<Long> bloomFilter;

    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    //将任意Java对象序列化为json并存储在string类型的key中，并且可以设置TTL过期时间
    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    //根据指定的key查询缓存，并反序列化为指定类型，利用缓存空值的方式解决缓存穿透问题,利用互斥锁解决缓存击穿问题
    public <R, ID> R queryWithPassThroughAndMutex(
           final String keyPrefix, final String lockKeyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback,
            Long time, TimeUnit unit, Long nullValueTTL, TimeUnit nullValueUnit){
        // 1.布隆过滤器判断id是否存在
        if (id instanceof Long && !bloomFilter.mightContain((Long)id)) {
            //不存在，直接返回错误
            log.info("布隆过滤器拦截，id:{}不存在", id);
            return null;
        }
        while (true) {
            // 2.从redis查询缓存
            String key = keyPrefix + id;
            String Json = stringRedisTemplate.opsForValue().get(key);
            // 3.判断是否存在
            if (StrUtil.isNotBlank(Json)) {
                // 存在，直接返回
                R result = JSONUtil.toBean(Json, type);
                return result;
            }

            // 4.判断是否位空值（缓存空值）
            if (Json != null) {
                // 是空值，返回不存在
                return null;
            }
            // 5.不存在，去数据库查找
            //TODO 互斥锁解决缓存击穿
            String lockKey = lockKeyPrefix + id;
            R result = null;
            try {
                boolean isLock = tryLock(lockKey);
                //判断是否获取锁成功
                if (!isLock) {
                    //失败，休眠并重试
                    Thread.sleep(50);
                    continue;
                } else {
                    // 再次检查缓存（Double Check）, 避免重复查询数据库
                    Json = stringRedisTemplate.opsForValue().get(key);
                    if (StrUtil.isNotBlank(Json)) {
                        R cached = JSONUtil.toBean(Json, type);
                        return cached;
                    }
                }
                //成功，根据id查询数据库
                result = dbFallback.apply(id);
                // 6.数据库不存在，返回错误信息
                if (result == null) {
                    //将空值写入redis（设置较短的过期时间）
                    stringRedisTemplate.opsForValue().set(key, "", nullValueTTL, nullValueUnit);
                    return null;
                }
                // 7.存在，写入redis
                // 生成TTL随机数,防止缓存雪崩
                Long random = (long) RandomUtil.randomInt(3, 10);
                stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(result),
                        time + random, unit);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } finally {
                //释放锁
                unlock(lockKey);
            }
            // 8.返回
            return result;
        }
    }

    //获取互斥锁
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    //释放锁
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }

}
