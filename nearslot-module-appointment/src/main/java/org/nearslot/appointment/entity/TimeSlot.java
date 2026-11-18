package org.nearslot.appointment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.nearslot.framework.mybatis.BaseEntity;

import java.time.LocalDateTime;

/**
 * 时段定义（uq_slot_occupy 唯一约束是防重复预约最后防线，research/08 §2）。
 *
 * **2026-11-17 修正**：
 * · 继承 {@link BaseEntity} —— created_at/updated_at 由 MetaObjectHandler 自动填充；
 * · {@code version} 加 {@link Version} —— 此前该列建了却从未参与 CAS，
 *   现由 OptimisticLockerInnerInterceptor 生效：并发发布/关闭同一时段时，
 *   后提交者影响行数为 0，业务侧可据此判定冲突而不是静默覆盖。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("time_slot")
public class TimeSlot extends BaseEntity {
    @TableId(type = IdType.INPUT)
    private Long id;
    private Long shopId;
    /** 履约执行者（ADR-021：餐品预约可为 null）。 */
    private Long serviceStaffId;
    private Long serviceItemId;
    private LocalDateTime startsAt;
    private LocalDateTime endsAt;
    private String tzId;
    private Integer capacity;
    private Integer status;
    /** 乐观锁版本号（@Version 生效需 OptimisticLockerInnerInterceptor）。 */
    @Version
    private Integer version;
    /** 预约该时段所需最低会员等级（0=无门槛）。 */
    private Integer minLevel;
}
