package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;


/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 超大王
 * @since 2025-09-17
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ShopTypeMapper shopTypeMapper;

    @Override
    public Result queryShopType() {
        // 1.从redis中查询商铺类型列表
        String shopTypeJson = stringRedisTemplate.opsForValue().get(RedisConstants.SHOP_TYPE_LIST);
        // 2.判断是否存在
        if(StrUtil.isNotBlank(shopTypeJson)){
            // 3.存在，将JSON字符串转换位对象列表后返回
            List<ShopType> shopTypeList = JSONUtil.toList(shopTypeJson, ShopType.class);
            return Result.ok(shopTypeList);
        }
        // 3.第一次一定不存在，去数据库查找
        List<ShopType> shopType = shopTypeMapper.selectAll();
        // 4.数据库不存在，返回错误信息
        if(shopType == null){
            return Result.fail("商铺类型不存在");
        }
        // 5.数据库存在，写入redis
        String jsonStr = JSONUtil.toJsonStr(shopType);
        stringRedisTemplate.opsForValue().set(RedisConstants.SHOP_TYPE_LIST,jsonStr,RedisConstants.SHOP_TYPE_TTL, TimeUnit.MINUTES);
        // 6.返回
        return Result.ok(shopType);
    }
}
