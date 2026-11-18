package org.nearslot.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C-ARCH-01/02 的可执行契约。
 *
 * 放在 nearslot-app：它聚合全部模块，ArchUnit 才能看到所有类。
 * 此前放在 framework（只看得到 common）时规则匹配 0 个类却"通过"（空跑假门禁）。
 *
 * 评审 F-001：新增 rulesMustMatchRealClasses，断言每个被约束的包真的导入了类，
 * 防止规则退化成"匹配 0 个类 -> 永远绿"。
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
        ArchRule rule = noClasses().that().resideInAPackage("..nearslot.content..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..nearslot.auth..", "..nearslot.shop..",
                "..nearslot.appointment..", "..nearslot.order..", "..nearslot.agent..")
            .because("C-ARCH-01: content 跨域走 common/api");
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

    @Test
    void rulesMustMatchRealClasses() {
        JavaClasses all = classes();
        long framework = all.stream().filter(c -> c.getPackageName().startsWith("org.nearslot.framework")).count();
        long common = all.stream().filter(c -> c.getPackageName().startsWith("org.nearslot.common")).count();
        long content = all.stream().filter(c -> c.getPackageName().startsWith("org.nearslot.content")).count();
        long agent = all.stream().filter(c -> c.getPackageName().startsWith("org.nearslot.agent")).count();
        assertTrue(framework > 0, "framework 必须被导入（否则规则空跑）");
        assertTrue(common > 0, "common 必须被导入（否则规则空跑）");
        assertTrue(content > 0, "content 必须被导入（否则规则空跑）");
        assertTrue(agent > 0, "agent 必须被导入（否则规则空跑）");
    }
}
