package com.kwikquant.report.infrastructure;

import com.kwikquant.report.domain.TradeRecord;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TradeRecordMapper {

    @Insert({
        "<script>",
        "INSERT INTO trade_records (report_id, time, side, price, amount, fee) VALUES",
        "<foreach collection='records' item='r' separator=','>",
        "  (#{r.reportId}, #{r.time}, #{r.side}, #{r.price}, #{r.amount}, #{r.fee})",
        "</foreach>",
        "</script>"
    })
    void batchInsert(@Param("records") List<TradeRecord> records);

    @Select("SELECT * FROM trade_records WHERE report_id = #{reportId} ORDER BY time ASC")
    List<TradeRecord> findByReportId(@Param("reportId") long reportId);
}
