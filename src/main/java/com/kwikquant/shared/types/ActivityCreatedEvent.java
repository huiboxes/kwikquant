package com.kwikquant.shared.types;

import java.time.Instant;
import java.util.Objects;

/**
 * 统一活动事件，由各模块 Listener 从领域事件转换后发布。
 *
 * <p>report 模块的 ActivityFeedService 监听此事件并持久化到 audit_logs 表。
 */
public record ActivityCreatedEvent(long userId, String type, String title, String subtitle, Instant timestamp) {

    private static final int MAX_TITLE_LENGTH = 200;
    private static final int MAX_SUBTITLE_LENGTH = 200;

    public ActivityCreatedEvent {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(title, "title must not be null");
        Objects.requireNonNull(timestamp, "timestamp must not be null");
        if (title.length() > MAX_TITLE_LENGTH) {
            throw new IllegalArgumentException("title max " + MAX_TITLE_LENGTH + " chars, got " + title.length());
        }
        if (subtitle != null && subtitle.length() > MAX_SUBTITLE_LENGTH) {
            throw new IllegalArgumentException(
                    "subtitle max " + MAX_SUBTITLE_LENGTH + " chars, got " + subtitle.length());
        }
    }
}
