package org.nearslot.shop.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 服务商域配置（`nearslot.shop.*`）。
 *
 * 附近检索半径是**业务半径**（上门服务与到店服务完全不同：到店 5km 够，上门可能 20km），
 * 必须可按业态配置，而不是写死 5。
 */
@Data
@Component
@ConfigurationProperties(prefix = "nearslot.shop")
public class ShopProperties {

    /** 列表默认每页条数。 */
    private int defaultPageSize = 10;

    /** 列表最大每页条数（超出被钳制）。 */
    private int maxPageSize = 50;

    /** 管理端列表默认每页条数。 */
    private int adminDefaultPageSize = 20;

    /** 附近检索默认半径（km）。 */
    private double nearbyRadiusKm = 5.0;

    /** 附近检索最多返回条数。 */
    private int nearbyMaxResults = 20;
}
