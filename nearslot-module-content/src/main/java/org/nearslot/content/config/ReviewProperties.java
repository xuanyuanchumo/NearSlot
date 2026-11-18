package org.nearslot.content.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 评价规则（`nearslot.review.*`）。
 *
 * 为什么外置：评价时限（多少天内可评）、字数上限、图片张数是**运营规则**，
 * 不同业态差异很大（到店服务通常 30 天，上门/维修可能 7 天），写死等于冻结运营。
 */
@Data
@Component
@ConfigurationProperties(prefix = "nearslot.review")
public class ReviewProperties {

    /** 履约完成后多少天内可评价（超期视为放弃）。 */
    private int windowDays = 30;

    /** 评分下限（含）。 */
    private int ratingMin = 1;

    /** 评分上限（含）。 */
    private int ratingMax = 5;

    /** 评价正文字数上限。 */
    private int maxContentLength = 1000;

    /** 最多上传图片张数。 */
    private int maxImages = 9;

    /** 商家回复字数上限。 */
    private int maxReplyLength = 1000;

    /** 无评分时的默认均分（放大 100 倍，500 = 5.00，与 technician.rating_avg 默认值一致）。 */
    private int defaultRatingAvg = 500;
}
