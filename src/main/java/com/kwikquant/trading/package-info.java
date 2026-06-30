@org.springframework.modulith.ApplicationModule(allowedDependencies = {
    "shared :: types",
    "shared :: infra",
    "account :: application",
    "account :: domain",
    "market :: application",
    "market :: domain",
    "risk"
})
package com.kwikquant.trading;
