package com.kwikquant;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.kwikquant.market.domain.TradingPairInfo;
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

    /** markError 是系统内部调用（WorkerOrchestratorService 健康检查失败），interfaces 层不得直接调用。 */
    @ArchTest
    static final ArchRule strategy_markError_not_called_from_interfaces = noClasses()
            .that()
            .resideInAPackage("..strategy.interfaces..")
            .should()
            .callMethod(StrategyLifecycleService.class, "markError", long.class, String.class);

    /** report domain 层不得依赖 Spring（纯领域逻辑）。package-info 的 @NamedInterface 是模块声明元数据，排除。 */
    @ArchTest
    static final ArchRule report_domain_must_not_depend_on_spring = noClasses()
            .that()
            .resideInAPackage("..report.domain..")
            .and()
            .areNotAnnotatedWith(org.springframework.modulith.NamedInterface.class)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("org.springframework..")
            .allowEmptyShould(true);

    /**
     * 合约尺寸（contractSize）只允许 market（pair 装载时张→币化）与 trading.infrastructure
     * （DefaultCcxtOrderAdapter 出站/回流边界的 PerpMath 换算）读取。张数是纯边界概念：
     * 域内钱数学（保证金/PnL/资金费/风控）一律币数量，任何新消费点想读 contractSize
     * 都意味着单位口径外泄，必须先过架构评审（如回测 pairSpecs 快照下发需扩白名单）。
     */
    @ArchTest
    static final ArchRule contractSize_confinedToExchangeBoundary = noClasses()
            .that()
            .resideOutsideOfPackages("com.kwikquant.market..", "com.kwikquant.trading.infrastructure..")
            .should()
            .callMethod(TradingPairInfo.class, "contractSize")
            .allowEmptyShould(true);
}
