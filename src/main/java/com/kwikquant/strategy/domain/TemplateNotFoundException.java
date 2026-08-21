package com.kwikquant.strategy.domain;

import com.kwikquant.shared.infra.ResourceNotFoundException;

/** 模板 key 不存在。ErrorCode {@code 7008 TEMPLATE_NOT_FOUND}，HTTP 404（StrategyExceptionHandler 映射）。 */
public class TemplateNotFoundException extends ResourceNotFoundException {
    private final String templateKey;

    public TemplateNotFoundException(String templateKey) {
        super("Template", templateKey);
        this.templateKey = templateKey;
    }

    public String templateKey() {
        return templateKey;
    }
}
