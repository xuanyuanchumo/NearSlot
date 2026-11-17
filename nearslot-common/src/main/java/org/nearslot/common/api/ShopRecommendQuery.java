package org.nearslot.common.api;

import java.util.List;

/** 服务商推荐/检索（跨模块契约）。agent 的 recommendShops 真值来源。 */
public interface ShopRecommendQuery {

    /** 按位置检索附近服务商；km 为半径。 */
    List<ShopView> nearby(double x, double y, double km);

    /** 分页列表（无位置信息时的兜底）。 */
    List<ShopView> page(int page, int size, Long typeId);

    /** 服务商视图：只含展示字段。 */
    record ShopView(Long shopId, String name, String address,
                    Double x, Double y, Long typeId) {}
}
