package com.kwikquant.report.domain;

import java.math.BigDecimal;
import java.time.Instant;

public record EquityPoint(Instant time, BigDecimal equity) {}
