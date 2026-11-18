package org.nearslot.appointment.dto;

import java.time.LocalDateTime;

/**
 * 预约单响应视图（评审 F-008 契约对齐 nearslot-web/types/api.ts Appointment）。
 *
 * 为什么不用实体直接暴露：
 * 1. F-008 曾把展示字段（startsAt/tzId/amountCents...）直接加进实体，
 *    MyBatis-Plus 把它们当成表列生成 SQL -> Unknown column，运行时 500。
 *    实体必须与表结构 1:1，展示字段只能在视图层。
 * 2. startsAt/tzId 来自 time_slot（跨表回填）；amountCents 是前端字段名兼容
 *    （后端列名 amount_cent -> 实体 amountCent -> 前端期望 amountCents）。
 * 3. shopName/technicianName 属于跨模块（shop/content）信息，需 common 契约
 *    （BL-FE-03 关联项），本视图暂不回填，前端为可选字段。
 */
public record AppointmentView(
        Long id,
        Long userId,
        Long slotId,
        Long shopId,
        Long serviceStaffId,
        String technicianName,
        Integer status,
        Integer amountCent,
        Integer amountCents,
        LocalDateTime startsAt,
        String tzId,
        LocalDateTime holdExpiresAt,
        LocalDateTime createdAt
) {}
