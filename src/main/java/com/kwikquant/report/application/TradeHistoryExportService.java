package com.kwikquant.report.application;

import com.kwikquant.report.domain.ReportExportFailedException;
import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class TradeHistoryExportService {

    private static final byte[] UTF8_BOM = new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private final ObjectMapper objectMapper;

    public TradeHistoryExportService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public byte[] exportCsv(List<TradeHistoryService.TradeHistoryItem> items) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bos.write(UTF8_BOM);
            PrintWriter writer = new PrintWriter(new OutputStreamWriter(bos, StandardCharsets.UTF_8));

            writer.println(
                    "orderId,accountId,symbol,side,orderType,amount,filledQty,filledAvgPrice,totalFee,totalVolume,status,createdAt,updatedAt");

            for (TradeHistoryService.TradeHistoryItem item : items) {
                writer.printf(
                        "%d,%d,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s%n",
                        item.orderId(),
                        item.accountId(),
                        sanitizeCsvField(item.symbol()),
                        sanitizeCsvField(item.side()),
                        sanitizeCsvField(item.orderType()),
                        plain(item.amount()),
                        plain(item.filledQty()),
                        plain(item.filledAvgPrice()),
                        plain(item.totalFee()),
                        plain(item.totalVolume()),
                        sanitizeCsvField(item.status()),
                        item.createdAt(),
                        item.updatedAt());
            }
            writer.flush();
            return bos.toByteArray();
        } catch (Exception e) {
            throw new ReportExportFailedException("CSV export failed", e);
        }
    }

    public byte[] exportJson(List<TradeHistoryService.TradeHistoryItem> items) {
        try {
            return objectMapper.writeValueAsBytes(items);
        } catch (JacksonException e) {
            throw new ReportExportFailedException("JSON export failed", e);
        }
    }

    /**
     * Prevent CSV injection: strip leading characters that could trigger formula execution
     * in spreadsheet applications (=, +, -, @, tab, carriage return).
     */
    private static String sanitizeCsvField(String value) {
        if (value == null) return "";
        String v = value;
        while (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0) {
            v = v.substring(1);
        }
        if (v.contains(",") || v.contains("\"") || v.contains("\n")) {
            v = "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    /**
     * Format a monetary BigDecimal as a plain decimal string. {@code %s}/{@code toString()}
     * would render very small values (e.g. {@code 0.0000001234}) in scientific notation
     * ({@code 1.234E-7}), which breaks CSV consumers that expect plain decimals.
     */
    private static String plain(BigDecimal value) {
        return value == null ? "" : value.toPlainString();
    }
}
