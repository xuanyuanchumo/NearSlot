package org.nearslot.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 约束登记册 C-ARCH-01/02 的可执行契约（指导核心 §2.1 四条铁律）。
 *
 * **为什么放在 nearslot-app**：ArchUnit 只能检查**测试类路径可见**的类。
 * 放在 framework 时，它只依赖 common，看不到 auth/shop/appointment/content 等模块，
 * 导致规则"空跑"（failed to check any classes）而不是真正校验。
 * app 是唯一聚合所有模块的地方（ADR-001），因此架构测试必须落在这里。
 *
 * 覆盖的铁律：
 * 1. module 之间禁止互相依赖 —— 跨模块只能走 common/api 契约或 MQ 事件；
 * 2. framework 不得 import 任何 module（零业务语义）；
 * 3. common 零依赖（只放常量/枚举/异常/Result/契约接口）。
 */
class ArchitectureRulesTest {

    private JavaClasses classes() {
        return new ClassFileImporter().importPackages("org.nearslot");
    }

    @Test
    void frameworkMustNotDependOnModules() {
        ArchRule rule = noClasses().that().resideInAPackage("..nearslot.framework..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..nearslot.auth..", "..nearslot.shop..",
                "..nearslot.appointment..", "..nearslot.order..",
                "..nearslot.content..", "..nearslot.agent..")
            .because("C-ARCH-02: framework 零业务语义");
        rule.check(classes());
    }

    @Test
    void commonMustBeDependencyFree() {
        // common 是依赖图叶子：不得依赖任何业务模块或 framework
        ArchRule rule = noClasses().that().resideInAPackage("..nearslot.common..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..nearslot.auth..", "..nearslot.shop..",
                "..nearslot.appointment..", "..nearslot.order..",
                "..nearslot.content..", "..nearslot.agent..", "..nearslot.framework..")
            .because("C-ARCH-01: common 零依赖");
        rule.check(classes());
    }

    @Test
    void contentMustNotDependOnBusinessModules() {
        // content 只允许 common + framework；"订单是否已完成"走 common/api 的 CompletedOrderQuery
        ArchRule rule = noClasses().that().resideInAPackage("..nearslot.content..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..nearslot.auth..", "..nearslot.shop..",
                "..nearslot.appointment..", "..nearslot.order..", "..nearslot.agent..")
            .because("C-ARCH-01: content 不得直接依赖业务模块，跨域走 common/api");
        rule.check(classes());
    }

    @Test
    void agentMustNotDependOnBusinessModules() {
        ArchRule rule = noClasses().that().resideInAPackage("..nearslot.agent..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..nearslot.auth..", "..nearslot.shop..",
                "..nearslot.appointment..", "..nearslot.order..", "..nearslot.content..")
            .because("C-ARCH-01: agent 只依赖 common 契约");
        rule.check(classes());
    }
}
