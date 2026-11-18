package org.nearslot.content.service;

import lombok.RequiredArgsConstructor;
import org.nearslot.common.enums.BaseCode;
import org.nearslot.common.exception.BaseException;
import org.nearslot.content.entity.ShopType;
import org.nearslot.content.mapper.ShopTypeMapper;
import org.nearslot.framework.id.IdGenerator;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 店铺分类（FR-10）。补齐参考项目都有、NearSlot 此前缺失的分类管理。
 * 只做基础 CRUD：分类是低频变更的字典数据，不值得上缓存层。
 */
@Service
@RequiredArgsConstructor
public class ShopTypeService {

    private final ShopTypeMapper mapper;

    public List<ShopType> list() {
        return mapper.selectList(
            com.baomidou.mybatisplus.core.toolkit.Wrappers.<ShopType>lambdaQuery()
                .orderByAsc(ShopType::getSort)
                .orderByAsc(ShopType::getId));
    }

    public Long create(String name, String icon, Integer sort) {
        if (name == null || name.isBlank()) {
            throw new BaseException(BaseCode.BAD_REQUEST, "分类名称不能为空");
        }
        ShopType t = new ShopType();
        t.setId(IdGenerator.nextId());
        t.setName(name);
        t.setIcon(icon == null ? "" : icon);
        t.setSort(sort == null ? 0 : sort);
        mapper.insert(t);
        return t.getId();
    }

    public void update(Long id, String name, String icon, Integer sort) {
        ShopType old = mapper.selectById(id);
        if (old == null) throw new BaseException(BaseCode.NOT_FOUND, "分类不存在");
        if (name != null && !name.isBlank()) old.setName(name);
        if (icon != null) old.setIcon(icon);
        if (sort != null) old.setSort(sort);
        mapper.updateById(old);
    }

    public void delete(Long id) {
        if (mapper.selectById(id) == null) throw new BaseException(BaseCode.NOT_FOUND, "分类不存在");
        mapper.deleteById(id);
    }
}
