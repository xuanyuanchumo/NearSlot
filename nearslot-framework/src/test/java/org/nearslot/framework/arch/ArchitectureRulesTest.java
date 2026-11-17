package org.nearslot.framework.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 约束登记册 C-ARCH-01/02 的可执行契约；feature 模块出现后自动生效。 */
class ArchitectureRulesTest {
    private JavaClasses classes() {
        JavaClasses c = new ClassFileImporter().importPackages("org.nearslot");
        assumeTrue(c.size() > 3, "feature 模块尚未落地，架构规则跳过");
        return c;
    }
    @Test void frameworkMustNotDependOnModules() {
        ArchRule rule = noClasses().that().resideInAPackage("..nearslot.framework..")
            .should().dependOnClassesThat().resideInAPackage("..nearslot.module..")
            .because("C-ARCH-02: framework 零业务语义");
        rule.check(classes());
    }
}
