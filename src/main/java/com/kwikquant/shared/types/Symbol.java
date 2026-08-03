package com.kwikquant.shared.types;

public record Symbol(String value) {
    public Symbol {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Symbol must not be blank");
        }
    }

    @Override
    public String toString() {
        return value;
    }

    /**
     * 从 BASE/QUOTE 拆出 quote 货币;非法 symbol(无斜杠)抛 IllegalArgumentException。
     * ExecutionService.processExecutionReport/processLiquidation 共用,消除重复
     * (architect MAJOR #10;TradingTransactionHelper 仍保留 InvalidOrderException 版本,不破坏其契约)。
     */
    public static String splitQuoteCurrency(String symbol) {
        String[] parts = symbol.split("/");
        if (parts.length != 2) {
            throw new IllegalArgumentException("invalid symbol (expect BASE/QUOTE): " + symbol);
        }
        return parts[1];
    }
}
