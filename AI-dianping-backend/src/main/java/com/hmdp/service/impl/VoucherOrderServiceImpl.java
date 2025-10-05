package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private final AtomicBoolean running = new AtomicBoolean(true);

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    // 使用单个线程的线程池来异步执行任务，保证任务的顺序性
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct
    private void init() {
        // 在服务启动时，提交异步处理任务
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    @PreDestroy
    private void onDestroy() {
        running.set(false);
        SECKILL_ORDER_EXECUTOR.shutdownNow();
    }

    // 内部类，用于处理消息队列中的订单任务
    private class VoucherOrderHandler implements Runnable {
        private final String queueName = "stream.orders";
        private final String groupName = "g1";
        private final String consumerName = "c1";

        @Override
        public void run() {
            log.info("订单处理线程已启动...");
            while (running.get() && !Thread.currentThread().isInterrupted()) {
                try {
                    // 1. 获取消息队列中的新消息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.orders >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from(groupName, consumerName),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed()) // 从 '>' 读取新消息
                    );

                    // 2. 判断消息是否为空
                    if (!running.get() || list == null || list.isEmpty()) {
                        // 如果为空，说明没有新消息，继续下一次循环
                        continue;
                    }

                    // 3. 解析消息并处理
                    MapRecord<String, Object, Object> record = list.get(0);
                    handleRecord(record);

                } catch (Exception e) {
                    // 如果主循环发生异常（比如Redis连接问题），记录日志并尝试处理Pending List
                    log.error("处理新订单时发生异常！", e);
                    handlePendingList();
                }
            }
        }

        // 专门处理Pending List中的异常消息
        private void handlePendingList() {
            log.info("开始处理Pending List中的异常消息...");
            while (running.get() && !Thread.currentThread().isInterrupted()) {
                try {
                    // 1. 获取Pending List中的消息 XREADGROUP GROUP g1 c1 COUNT 1 STREAMS stream.orders 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from(groupName, consumerName),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0")) // 从 '0' 读取Pending List
                    );

                    // 2. 判断消息是否为空
                    if (!running.get() || list == null || list.isEmpty()) {
                        // 如果为空，说明Pending List已处理完毕，跳出循环
                        log.info("Pending List处理完毕，退出异常处理模式。");
                        break;
                    }

                    // 3. 解析消息并处理
                    MapRecord<String, Object, Object> record = list.get(0);
                    handleRecord(record);

                } catch (Exception e) {
                    // 如果处理Pending List时再次发生异常，记录日志后稍作等待，避免CPU空转
                    log.error("处理Pending List消息时再次发生异常！", e);
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException interruptedException) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        // 统一的消息处理方法
        private void handleRecord(MapRecord<String, Object, Object> record) {
            Map<Object, Object> value = record.getValue();
            VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);

            // ========================= BUG 1 修复点 =========================
            // 从消息体中获取 orderId 并设置到实体类中，防止 "id cannot be null"
            Long orderId = Long.valueOf(value.get("orderId").toString());
            voucherOrder.setId(orderId);
            // =============================================================

            // 创建订单（包含数据库操作）
            createVoucherOrder(voucherOrder);

            // 确认消息 XACK
            stringRedisTemplate.opsForStream().acknowledge(queueName, groupName, record.getId());
            log.info("订单处理成功并ACK，订单ID: {}", orderId);
        }
    }

    // @Transactional // 事务注解可以加在这里或 createVoucherOrder 方法上
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();

        // 使用分布式锁确保用户操作的幂等性
        RLock redisLock = redissonClient.getLock("lock:order:" + userId);
        boolean isLock = redisLock.tryLock();
        if (!isLock) {
            log.error("不允许重复下单！用户ID: {}", userId);
            return;
        }

        try {
            // 再次查询订单，作为兜底校验
            Long count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
            if (count > 0) {
                log.error("数据库已存在该订单，不允许重复下单！用户ID: {}", userId);
                return;
            }

            // 扣减库存
            boolean success = seckillVoucherService.update()
                    .setSql("stock = stock - 1")
                    .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0)
                    .update();
            if (!success) {
                log.error("数据库库存不足！");
                return;
            }

            // 创建订单
            save(voucherOrder);
        } finally {
            // 释放锁
            redisLock.unlock();
        }
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");
        // 1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );
        int r = result.intValue();
        // 2.判断结果是否为0
        if (r != 0) {
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        // 3.返回订单id
        return Result.ok(orderId);
    }
}