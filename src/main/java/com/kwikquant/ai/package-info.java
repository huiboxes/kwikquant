@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {
            "shared",
            "shared :: types",
            "shared :: infra",
            "account :: application",
            "account :: domain",
            "strategy :: application",
            "strategy :: domain"
        })
package com.kwikquant.ai;
