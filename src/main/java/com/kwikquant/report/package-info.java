@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {
            "shared :: types",
            "shared :: infra",
            "account :: application",
            "market :: application",
            "market :: domain",
            "trading :: domain",
            "trading :: application"
        })
package com.kwikquant.report;
