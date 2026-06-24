package com.kwikquant.shared.infra;

public interface AuditRepository {
    void save(AuditEntry entry);
}
