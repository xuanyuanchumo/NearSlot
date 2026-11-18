package org.nearslot.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AI 助手参数（`nearslot.agent.*`）。
 *
 * 高危操作的确认令牌有效期与规划最大轮数属于**成本与安全参数**：
 * · 轮数过多 → token 成本线性膨胀（research/04 §1 已记录 21.6 万 units/月的教训）；
 * · 确认令牌过长 → 用户离开后可被重放；过短 → 正常操作被打断。
 */
@Data
@Component
@ConfigurationProperties(prefix = "nearslot.agent")
public class AgentProperties {

    /** 高危操作二次确认令牌有效期（秒）。 */
    private int confirmTokenTtlSeconds = 300;

    /** 两阶段规划最多调用工具轮数（防 token 成本失控）。 */
    private int maxToolRounds = 5;
}
