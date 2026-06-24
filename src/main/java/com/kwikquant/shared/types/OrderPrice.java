package com.kwikquant.shared.types;

import java.math.BigDecimal;

public sealed interface OrderPrice {

    record Market() implements OrderPrice {
        public static final Market INSTANCE = new Market();
    }

    record Limit(BigDecimal price) implements OrderPrice {
        public Limit {
            requirePositive(price, "price");
        }
    }

    record StopMarket(BigDecimal stopPrice) implements OrderPrice {
        public StopMarket {
            requirePositive(stopPrice, "stopPrice");
        }
    }

    record StopLimit(BigDecimal stopPrice, BigDecimal price) implements OrderPrice {
        public StopLimit {
            requirePositive(stopPrice, "stopPrice");
            requirePositive(price, "price");
        }
    }

    record TakeProfitMarket(BigDecimal stopPrice) implements OrderPrice {
        public TakeProfitMarket {
            requirePositive(stopPrice, "stopPrice");
        }
    }

    record TakeProfitLimit(BigDecimal stopPrice, BigDecimal price) implements OrderPrice {
        public TakeProfitLimit {
            requirePositive(stopPrice, "stopPrice");
            requirePositive(price, "price");
        }
    }

    record TrailingStop(BigDecimal callbackRate, BigDecimal activationPrice) implements OrderPrice {
        public TrailingStop {
            if (callbackRate == null || callbackRate.signum() <= 0 || callbackRate.compareTo(BigDecimal.ONE) >= 0) {
                throw new IllegalArgumentException("callbackRate must be in (0, 1), got: " + callbackRate);
            }
            if (activationPrice != null && activationPrice.signum() <= 0) {
                throw new IllegalArgumentException("activationPrice must be positive, got: " + activationPrice);
            }
        }

        public TrailingStop(BigDecimal callbackRate) {
            this(callbackRate, null);
        }
    }

    private static void requirePositive(BigDecimal value, String name) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException(name + " must be positive, got: " + value);
        }
    }
}
