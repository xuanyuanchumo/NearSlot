package org.nearslot.shop.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nearslot.common.context.UserHolder;
import org.nearslot.common.enums.BaseCode;
import org.nearslot.common.exception.BaseException;
import org.nearslot.framework.bloom.BloomFilterHandler;
import org.nearslot.framework.cache.CacheManager;
import org.nearslot.framework.mq.CacheInvalidationPublisher;
import org.nearslot.shop.entity.ServiceItem;
import org.nearslot.shop.entity.Technician;
import org.nearslot.shop.entity.TechnicianSkill;
import org.nearslot.shop.mapper.ServiceItemMapper;
import org.nearslot.shop.mapper.ShopMapper;
import org.nearslot.shop.mapper.TechnicianMapper;
import org.nearslot.shop.mapper.TechnicianSkillMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 技师-技能关联单测（FR-2.4 / business-domain.md §3.2）。
 *
 * 背景：此前 Technician 只有 name/level，没有技能关联，
 * 导致「用户选了肩颈推拿却分配只会美甲的技师」。本测试锁住：
 * · 跨店铺绑定被拒（否则按服务项筛技师会把别家技师筛出来）；
 * · 重复绑定转成更新熟练度（幂等），不依赖唯一键异常；
 * · 熟练度钳制到 1-5；
 * · 列表接口批量取服务项（避免 N+1）。
 */
class TechnicianSkillManageTest {

    private static final Long SHOP_ID = 5001L;
    private static final Long TECH_ID = 6001L;
    private static final Long ITEM_ID = 7001L;

    private ShopMapper shopMapper;
    private TechnicianMapper technicianMapper;
    private ServiceItemMapper serviceItemMapper;
    private TechnicianSkillMapper skillMapper;
    private CacheInvalidationPublisher publisher;
    private CacheManager cacheManager;
    private ShopAdminService service;

    @BeforeEach
    void setUp() {
        shopMapper = mock(ShopMapper.class);
        technicianMapper = mock(TechnicianMapper.class);
        serviceItemMapper = mock(ServiceItemMapper.class);
        skillMapper = mock(TechnicianSkillMapper.class);
        publisher = mock(CacheInvalidationPublisher.class);
        cacheManager = mock(CacheManager.class);
        service = new ShopAdminService(shopMapper, technicianMapper, serviceItemMapper,
            mock(BloomFilterHandler.class), publisher, cacheManager, skillMapper);
        UserHolder.set(1001L);
        UserHolder.setRole("PLATFORM");
    }

    @AfterEach
    void tearDown() {
        UserHolder.clear();
    }

    private Technician tech(long shopId) {
        Technician t = new Technician();
        t.setId(TECH_ID);
        t.setShopId(shopId);
        return t;
    }

    private ServiceItem item(long shopId) {
        ServiceItem i = new ServiceItem();
        i.setId(ITEM_ID);
        i.setShopId(shopId);
        i.setName("肩颈推拿");
        i.setDurationMin(60);
        i.setPriceCent(19800);
        return i;
    }

    @Test
    @DisplayName("bindSkill: 首次绑定 -> 插入记录，熟练度缺省 3")
    void bindFirstTime() {
        when(technicianMapper.selectById(TECH_ID)).thenReturn(tech(SHOP_ID));
        when(serviceItemMapper.selectById(ITEM_ID)).thenReturn(item(SHOP_ID));
        when(skillMapper.selectOne(any())).thenReturn(null);

        Long id = service.bindSkill(TECH_ID, ITEM_ID, null);

        assertNotNull(id);
        verify(skillMapper).insert(argThat((TechnicianSkill s) ->
            s.getTechnicianId().equals(TECH_ID)
                && s.getServiceItemId().equals(ITEM_ID)
                && s.getProficiency() == 3));
    }

    @Test
    @DisplayName("bindSkill: 重复绑定 -> 更新熟练度而不是重复插入（幂等）")
    void bindIdempotent() {
        when(technicianMapper.selectById(TECH_ID)).thenReturn(tech(SHOP_ID));
        when(serviceItemMapper.selectById(ITEM_ID)).thenReturn(item(SHOP_ID));
        TechnicianSkill existing = new TechnicianSkill();
        existing.setId(8001L);
        existing.setTechnicianId(TECH_ID);
        existing.setServiceItemId(ITEM_ID);
        existing.setProficiency(2);
        when(skillMapper.selectOne(any())).thenReturn(existing);

        service.bindSkill(TECH_ID, ITEM_ID, 5);

        assertEquals(5, existing.getProficiency());
        verify(skillMapper).updateById(existing);
        verify(skillMapper, never()).insert(any(TechnicianSkill.class));
    }

    @Test
    @DisplayName("bindSkill: 熟练度越界 -> 钳制到 1-5")
    void bindClampsProficiency() {
        when(technicianMapper.selectById(TECH_ID)).thenReturn(tech(SHOP_ID));
        when(serviceItemMapper.selectById(ITEM_ID)).thenReturn(item(SHOP_ID));
        when(skillMapper.selectOne(any())).thenReturn(null);

        service.bindSkill(TECH_ID, ITEM_ID, 99);

        verify(skillMapper).insert(argThat((TechnicianSkill s) -> s.getProficiency() == 5));
    }

    @Test
    @DisplayName("bindSkill: 服务项与技师不同店 -> 40001（跨店绑定会污染技能筛选）")
    void bindRejectsCrossShop() {
        when(technicianMapper.selectById(TECH_ID)).thenReturn(tech(SHOP_ID));
        when(serviceItemMapper.selectById(ITEM_ID)).thenReturn(item(9999L));

        BaseException ex = assertThrows(BaseException.class,
            () -> service.bindSkill(TECH_ID, ITEM_ID, 3));

        assertEquals(BaseCode.BAD_REQUEST, ex.getCode());
        verify(skillMapper, never()).insert(any(TechnicianSkill.class));
    }

    @Test
    @DisplayName("bindSkill: 技师不存在 -> 40401")
    void bindRejectsMissingTechnician() {
        when(technicianMapper.selectById(TECH_ID)).thenReturn(null);

        BaseException ex = assertThrows(BaseException.class,
            () -> service.bindSkill(TECH_ID, ITEM_ID, 3));

        assertEquals(BaseCode.NOT_FOUND, ex.getCode());
    }

    @Test
    @DisplayName("listSkills: 批量取服务项，返回名称/时长/价格（无 N+1）")
    void listSkillsBatchLoads() {
        when(technicianMapper.selectById(TECH_ID)).thenReturn(tech(SHOP_ID));
        TechnicianSkill sk = new TechnicianSkill();
        sk.setId(8001L);
        sk.setTechnicianId(TECH_ID);
        sk.setServiceItemId(ITEM_ID);
        sk.setProficiency(4);
        when(skillMapper.selectList(any())).thenReturn(java.util.List.of(sk));
        when(serviceItemMapper.selectBatchIds(anyCollection())).thenReturn(java.util.List.of(item(SHOP_ID)));

        var rows = service.listSkills(TECH_ID);

        assertEquals(1, rows.size());
        assertEquals("肩颈推拿", rows.get(0).get("serviceItemName"));
        assertEquals(60, rows.get(0).get("durationMin"));
        assertEquals(4, rows.get(0).get("proficiency"));
        // 关键：一次批量查询，而不是循环内 selectById
        verify(serviceItemMapper, times(1)).selectBatchIds(anyCollection());
    }

    @Test
    @DisplayName("unbindSkill: 删除关联（归属校验基于技师店铺）")
    void unbindRemoves() {
        when(technicianMapper.selectById(TECH_ID)).thenReturn(tech(SHOP_ID));
        when(skillMapper.delete(any())).thenReturn(1);

        assertDoesNotThrow(() -> service.unbindSkill(TECH_ID, ITEM_ID));

        verify(skillMapper).delete(any());
    }
}
