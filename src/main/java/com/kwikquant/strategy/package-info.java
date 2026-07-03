@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {
            "shared",
            "shared :: types",
            "shared :: infra",
            "account :: application",
            "account :: domain",
            "report :: application"
        })
package com.kwikquant.strategy;
