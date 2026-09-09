package com.kwikquant.report.domain;

/**
 * 报告导出请求形态上不可履行(组合/PERP 报告的导出契约不存在,import 闭环 SPOT-only)。
 * 与 {@link ReportExportFailedException}(序列化/IO 真故障,500)区分:本异常映射 422,
 * message 直接透出——"不支持"是确定性事实,重试无意义,500 会污染服务端错误告警。
 */
public class ReportExportUnsupportedException extends RuntimeException {
    public ReportExportUnsupportedException(String message) {
        super(message);
    }
}
