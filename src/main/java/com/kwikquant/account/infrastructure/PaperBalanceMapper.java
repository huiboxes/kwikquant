package com.kwikquant.account.infrastructure;

import com.kwikquant.account.domain.PaperBalance;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 模拟盘余额 MyBatis mapper。CAS 乐观锁模式同 PositionMapper/OrderMapper。
 */
@Mapper
public interface PaperBalanceMapper {

    @Select(
            """
            SELECT id, account_id, currency, free, used, total, version, created_at, updated_at
            FROM paper_balances WHERE account_id = #{accountId} AND currency = #{currency}
            """)
    PaperBalance findByAccountAndCurrency(@Param("accountId") long accountId, @Param("currency") String currency);

    @Select(
            """
            SELECT id, account_id, currency, free, used, total, version, created_at, updated_at
            FROM paper_balances WHERE account_id = #{accountId}
            """)
    List<PaperBalance> findByAccount(@Param("accountId") long accountId);

    @Insert(
            """
            INSERT INTO paper_balances (account_id, currency, free, used, total, version)
            VALUES (#{accountId}, #{currency}, #{free}, #{used}, #{total}, #{version})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(PaperBalance balance);

    /**
     * CAS 更新:WHERE version = #{version} 匹配则更新 + version+1,返回 1;并发改则返回 0。
     */
    @Update(
            """
            UPDATE paper_balances
            SET free = #{free}, used = #{used}, total = #{total}, version = version + 1, updated_at = now()
            WHERE id = #{id} AND version = #{version}
            """)
    int casUpdate(PaperBalance balance);

    /** 重置用:清空该账户所有余额行。 */
    @Delete("DELETE FROM paper_balances WHERE account_id = #{accountId}")
    int deleteByAccount(@Param("accountId") long accountId);
}
