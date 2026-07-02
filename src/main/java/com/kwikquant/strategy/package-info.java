@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {
            "shared",
            "shared :: types",
            "shared :: infra",
            "account :: application",
            "account :: domain"
        })
package com.kwikquant.strategy;
