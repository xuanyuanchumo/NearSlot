package org.nearslot.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 约束登记册 C-ARCH-01/02 的可执行契约（G2/G3 门禁）。Phase 1 模块落地后自动启用。 */
class ArchitectureRulesTest {
    private JavaClasses classes() {
        JavaClasses c = new ClassFileImporter().importPackages("org.nearslot");
        assumeTrue(c.size() > 0, "模块骨架未落地，架构规则跳过（Phase 1 启用）");
        return c;
    }

    @Test
    void moduleMustNotDependOnOtherModules() {
        ArchRule rule = noClasses().that().resideInAPackage("..nearslot.module..")
            .should().dependOnClassesThat().resideInAPackage("..nearslot.module..")
            .because("C-ARCH-01: module 之间禁止互相依赖");
        rule.check(classes());
    }

    @Test
    void frameworkMustNotDependOnModules() {
        ArchRule rule = noClasses().that().resideInAPackage("..nearslot.framework..")
            .should().dependOnClassesThat().resideInAPackage("..nearslot.module..")
            .because("C-ARCH-02: framework 零业务语义");
        rule.check(classes());
    }
}
