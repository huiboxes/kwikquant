@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {
            "shared",
            "shared :: types",
            "shared :: infra",
            "account :: application",
            "market :: application",
            "strategy :: application",
            "trading :: application",
            "risk :: application",
            "report :: application"
        })
package com.kwikquant.mcp;
