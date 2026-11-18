package org.nearslot.content.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nearslot.common.enums.BaseCode;
import org.nearslot.common.exception.BaseException;
import org.nearslot.content.entity.ShopType;
import org.nearslot.content.mapper.ShopTypeMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 店铺分类单测（FR-10）。 */
class ShopTypeServiceTest {

    private ShopTypeMapper mapper;
    private ShopTypeService service;

    @BeforeEach
    void setUp() {
        mapper = mock(ShopTypeMapper.class);
        service = new ShopTypeService(mapper);
    }

    @Test
    @DisplayName("create: 名称为空 -> 40001")
    void createRejectsBlank() {
        BaseException ex = assertThrows(BaseException.class,
            () -> service.create("  ", "i", 1));
        assertEquals(BaseCode.BAD_REQUEST, ex.getCode());
    }

    @Test
    @DisplayName("create: 正常创建（sort 缺省 0）")
    void createWorks() {
        Long id = service.create("推拿", null, null);

        assertNotNull(id);
        verify(mapper).insert(argThat((ShopType t) ->
            "推拿".equals(t.getName()) && t.getSort() == 0 && "".equals(t.getIcon())));
    }

    @Test
    @DisplayName("update: 不存在 -> 40401")
    void updateMissing() {
        when(mapper.selectById(1L)).thenReturn(null);
        BaseException ex = assertThrows(BaseException.class, () -> service.update(1L, "x", null, null));
        assertEquals(BaseCode.NOT_FOUND, ex.getCode());
    }

    @Test
    @DisplayName("update: 只改传入字段，其余保留")
    void updatePartial() {
        ShopType old = new ShopType();
        old.setId(1L);
        old.setName("旧名");
        old.setIcon("old.png");
        old.setSort(9);
        when(mapper.selectById(1L)).thenReturn(old);

        service.update(1L, "新名", null, null);

        assertEquals("新名", old.getName());
        assertEquals("old.png", old.getIcon(), "未传的 icon 应保留");
        assertEquals(9, old.getSort(), "未传的 sort 应保留");
        verify(mapper).updateById(old);
    }

    @Test
    @DisplayName("delete: 不存在 -> 40401")
    void deleteMissing() {
        when(mapper.selectById(1L)).thenReturn(null);
        BaseException ex = assertThrows(BaseException.class, () -> service.delete(1L));
        assertEquals(BaseCode.NOT_FOUND, ex.getCode());
    }

    @Test
    @DisplayName("delete: 存在 -> 删除")
    void deleteWorks() {
        when(mapper.selectById(1L)).thenReturn(new ShopType());
        service.delete(1L);
        verify(mapper).deleteById(1L);
    }

    @Test
    @DisplayName("list: 按 sort 升序")
    void listOrdered() {
        when(mapper.selectList(any())).thenReturn(List.of());
        assertTrue(service.list().isEmpty());
        verify(mapper).selectList(any());
    }
}
