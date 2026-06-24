package com.kwikquant;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

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
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("org.springframework..")
            .allowEmptyShould(true);
}
