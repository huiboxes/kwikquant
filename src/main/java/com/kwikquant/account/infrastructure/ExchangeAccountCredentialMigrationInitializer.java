package com.kwikquant.account.infrastructure;

import com.kwikquant.account.application.ExchangeAccountCredentialMigration;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/** Runs after singleton proxies exist but before the application context is ready. */
@Component
class ExchangeAccountCredentialMigrationInitializer implements SmartInitializingSingleton {

    private final ExchangeAccountCredentialMigration migration;

    ExchangeAccountCredentialMigrationInitializer(ExchangeAccountCredentialMigration migration) {
        this.migration = migration;
    }

    @Override
    public void afterSingletonsInstantiated() {
        migration.migrateOrFail();
    }
}
