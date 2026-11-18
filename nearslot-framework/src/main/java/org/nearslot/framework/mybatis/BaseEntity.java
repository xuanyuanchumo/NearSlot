package org.nearslot.framework.mybatis;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 业务实体公共字段基类（docs/database/schema.md §1：每表必带 created_at / updated_at）。
 *
 * **为什么放在 framework 而不是 common**：
 * 本类需要 MyBatis-Plus 的 {@code @TableField}/{@code FieldFill} 注解，
 * 而 C-ARCH-01 铁律要求 common **零依赖**（只放常量/枚举/异常/Result/跨模块契约）。
 * framework 本就是"通用能力层"，且已依赖 MP core（IdWorker），放这里不新增耦合。
 *
 * 注意：字段上的 {@code @TableField(fill = ...)} 必须与
 * {@code MybatisPlusConfig#metaObjectHandler} 配合才生效——
 * 只标注解不注册 handler，字段永远是 null（这正是此前 created_at/updated_at 的空转原因）。
 */
@Data
public abstract class BaseEntity implements Serializable {

    /** 创建时间：插入时自动填充。 */
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 更新时间：插入与更新时都自动填充。 */
    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
