package org.nearslot.shop.api;

import lombok.RequiredArgsConstructor;
import org.nearslot.common.api.ShopRecommendQuery;
import org.nearslot.shop.entity.Shop;
import org.nearslot.shop.service.ShopService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/** 服务商推荐/检索实现（薄封装 ShopService）。 */
@Service
@RequiredArgsConstructor
public class ShopRecommendQueryImpl implements ShopRecommendQuery {

    private final ShopService shopService;

    @Override
    public List<ShopView> nearby(double x, double y, double km) {
        return shopService.searchNearby(x, y, km).stream().map(this::toView).toList();
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<ShopView> page(int page, int size, Long typeId) {
        Map<String, Object> res = shopService.page(page, size, typeId);
        Object items = res.get("items");
        if (!(items instanceof List<?> list)) return List.of();
        return list.stream()
            .filter(Shop.class::isInstance)
            .map(e -> toView((Shop) e))
            .toList();
    }

    private ShopView toView(Shop s) {
        return new ShopView(s.getId(), s.getName(), s.getAddress(),
            s.getX() == null ? null : Double.valueOf(String.valueOf(s.getX())),
            s.getY() == null ? null : Double.valueOf(String.valueOf(s.getY())),
            s.getTypeId());
    }
}
