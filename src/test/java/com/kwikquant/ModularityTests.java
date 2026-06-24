package com.kwikquant;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTests {

    @Test
    void verifyModuleStructure() {
        ApplicationModules.of(KwikquantApplication.class).verify();
    }
}
