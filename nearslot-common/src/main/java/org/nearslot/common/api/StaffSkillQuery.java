package org.nearslot.common.api;

import java.util.Set;

/** 服务者技能查询（跨模块契约，遵守依赖铁律 C-ARCH-01）。 */
public interface StaffSkillQuery {
    /** 能做指定服务项的服务者 ID 集合。 */
    Set<Long> staffIdsByItem(Long serviceItemId);
}
