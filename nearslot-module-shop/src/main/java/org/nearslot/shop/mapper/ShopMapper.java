package org.nearslot.shop.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.nearslot.shop.entity.Shop;

@Mapper
public interface ShopMapper extends BaseMapper<Shop> {
}
