package org.nearslot.shop.service;

import lombok.RequiredArgsConstructor;
import org.nearslot.common.api.StaffSkillQuery;
import org.nearslot.shop.mapper.TechnicianSkillMapper;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StaffSkillQueryImpl implements StaffSkillQuery {
    private final TechnicianSkillMapper mapper;

    @Override
    public Set<Long> staffIdsByItem(Long serviceItemId) {
        if (serviceItemId == null) return Set.of();
        return mapper.selectList(
            com.baomidou.mybatisplus.core.toolkit.Wrappers.<org.nearslot.shop.entity.TechnicianSkill>lambdaQuery()
                .eq(org.nearslot.shop.entity.TechnicianSkill::getServiceItemId, serviceItemId))
            .stream().map(org.nearslot.shop.entity.TechnicianSkill::getTechnicianId)
            .collect(Collectors.toSet());
    }
}
