@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {
            "shared",
            "shared :: types",
            "shared :: infra",
            "account :: application",
            "account :: domain",
            "market :: application",
            "market :: domain",
            "strategy :: application",
            "strategy :: domain",
            "trading :: application",
            "trading :: domain",
            "risk :: application",
            "risk :: domain",
            "report :: application",
            "report :: domain"
        })
package com.kwikquant.mcp;
