package com.kwikquant.strategy.domain;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class StrategyCodeTest {

    @Test
    void create_setsDraftAndPython() {
        StrategyCode c = StrategyCode.create(1L, 1, "def on_bar(): pass", "v1");
        assertEquals(StrategyCodeStatus.DRAFT, c.getStatus());
        assertEquals("python", c.getLanguage());
        assertEquals(1, c.getVersionNumber());
    }

    @Test
    void transitionTo_draftToPublishedToArchived() {
        StrategyCode c = StrategyCode.create(1L, 1, "code", null);
        c.transitionTo(StrategyCodeStatus.PUBLISHED);
        c.transitionTo(StrategyCodeStatus.ARCHIVED);
        assertEquals(StrategyCodeStatus.ARCHIVED, c.getStatus());
    }

    @Test
    void transitionTo_publishedToDraftIllegal() {
        StrategyCode c = StrategyCode.create(1L, 1, "code", null);
        c.transitionTo(StrategyCodeStatus.PUBLISHED);
        // PUBLISHED → DRAFT 非法（只能 → ARCHIVED）
        assertThrows(IllegalStrategyCodeStateTransitionException.class, () -> c.transitionTo(StrategyCodeStatus.DRAFT));
    }

    @Test
    void transitionTo_archivedToAnythingIllegal() {
        StrategyCode c = StrategyCode.create(1L, 1, "code", null);
        c.transitionTo(StrategyCodeStatus.PUBLISHED);
        c.transitionTo(StrategyCodeStatus.ARCHIVED);
        assertThrows(
                IllegalStrategyCodeStateTransitionException.class, () -> c.transitionTo(StrategyCodeStatus.PUBLISHED));
    }
}
