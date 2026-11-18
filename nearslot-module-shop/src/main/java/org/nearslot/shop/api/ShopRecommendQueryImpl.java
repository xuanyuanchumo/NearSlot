package org.nearslot.shop.api;

import lombok.RequiredArgsConstructor;
import org.nearslot.common.api.ShopRecommendQuery;
import org.nearslot.shop.entity.Shop;
import org.nearslot.shop.service.ShopService;
import org.springframework.stereotype.Service;

import java.util.List;

/** 服务商推荐/检索实现（薄封装 ShopService）。 */
@Service
@RequiredArgsConstructor
public class ShopRecommendQueryImpl implements ShopRecommendQuery {

    private final ShopService shopService;

    @Override
    public List<ShopView> nearby(double x, double y, double km) {
        return shopService.searchNearby(x, y, km).stream().map(this::toView).toList();
    }

    /**
     * 分页查询（ShopService 已返回强类型 PageResult，无需再拆 Map）。
     *
     * 此前这里是 `Map<String,Object> res = ...; res.get("items")` + instanceof 过滤，
     * 属于"类型信息在服务层丢失、调用方再猜回来"的反模式：
     * 字段名写错只会得到空列表而不会编译失败。改强类型后这类错误编译期即可发现。
     */
    @Override
    public List<ShopView> page(int page, int size, Long typeId) {
        return shopService.page(page, size, typeId).items().stream()
            .map(this::toView)
            .toList();
    }

    private ShopView toView(Shop s) {
        return new ShopView(s.getId(), s.getName(), s.getAddress(),
            s.getX() == null ? null : Double.valueOf(String.valueOf(s.getX())),
            s.getY() == null ? null : Double.valueOf(String.valueOf(s.getY())),
            s.getTypeId());
    }
}
