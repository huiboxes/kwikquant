@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {
            "shared",
            "shared :: types",
            "shared :: infra",
            "account :: application",
            "market :: application",
            "market :: domain",
            "strategy :: application",
            "trading :: application",
            "trading :: domain",
            "risk :: application",
            "risk :: domain",
            "report :: application"
        })
package com.kwikquant.mcp;
