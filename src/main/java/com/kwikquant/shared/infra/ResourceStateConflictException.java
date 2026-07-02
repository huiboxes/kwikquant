package com.kwikquant.shared.infra;

/**
 * 资源状态冲突异常（CAS 失败、非法状态转换等）。
 *
 * <p>映射到 {@link ErrorCode#RESOURCE_STATE_CONFLICT}(4009)，HTTP 409 CONFLICT。供策略状态机 CAS
 * 失败、代码版本 publish 竞争等场景使用。
 */
public class ResourceStateConflictException extends RuntimeException {

    private final String resourceType;

    public ResourceStateConflictException(String resourceType) {
        super("Resource state conflict: " + resourceType);
        this.resourceType = resourceType;
    }

    public String resourceType() {
        return resourceType;
    }
}
