package com.kwikquant;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.kwikquant.strategy.application.StrategyLifecycleService;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.kwikquant", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTests {

    @ArchTest
    static final ArchRule domain_should_not_depend_on_spring = noClasses()
            .that()
            .resideInAPackage("..domain..")
            .and()
            // package-info uses @NamedInterface (Spring Modulith metadata) to declare
            // named interfaces — this is module declaration, not a Spring runtime dependency.
            .areNotAnnotatedWith(org.springframework.modulith.NamedInterface.class)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("org.springframework..")
            .allowEmptyShould(true);

    /** spec-review S-7: markError 是系统内部调用（WorkerOrchestratorService 健康检查失败），interfaces 层不得直接调用。 */
    @ArchTest
    static final ArchRule strategy_markError_not_called_from_interfaces = noClasses()
            .that()
            .resideInAPackage("..strategy.interfaces..")
            .should()
            .callMethod(StrategyLifecycleService.class, "markError", long.class, String.class);

    /** Wave 7: report domain 层不得依赖 Spring（纯领域逻辑）。 */
    @ArchTest
    static final ArchRule report_domain_must_not_depend_on_spring = noClasses()
            .that()
            .resideInAPackage("..report.domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("org.springframework..")
            .allowEmptyShould(true);
}
