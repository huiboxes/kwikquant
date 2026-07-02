@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {
            "shared :: types",
            "shared :: infra",
            "account :: application",
            "market :: application",
            "market :: domain",
            "trading :: domain",
            "trading :: infrastructure"
        })
package com.kwikquant.report;
