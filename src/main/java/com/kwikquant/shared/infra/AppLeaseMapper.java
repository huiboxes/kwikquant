package com.kwikquant.shared.infra;

import java.time.OffsetDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 单节点 lease mapper(Wave 1.5-c)。单行 lease(id=1),原子条件 UPDATE acquire(无 race)。
 *
 * <p>{@code @Mapper} 注解自动发现(项目无 @MapperScan,参照 {@code McpTokenMapper})。
 * {@code map-underscore-to-camel-case} 自动映射。
 */
@Mapper
public interface AppLeaseMapper {

    /** 取 lease 行(供拒绝日志:报哪个 node 持有)。无行返 null(V51 预填 id=1,正常不无行)。 */
    @Select("SELECT node_id, acquired_at, last_seen_at FROM app_lease WHERE id = 1")
    AppLeaseRow selectForInfo();

    /**
     * 原子 acquire:仅当 lease 可获取才覆盖。返 affectedRows(1=成功 acquire;0=被活跃持有,拒绝启动)。
     *
     * <p>条件:node_id 空(无持有) OR node_id=self(重连/重启) OR last_seen_at 过期(前一实例崩溃)。
     * 原子性:单条 UPDATE + WHERE 条件,无需"先 SELECT 后 UPDATE"的 race(并发两实例同时 acquire,
     * 只有一个 UPDATE 命中 → 另一个 affectedRows=0 → 拒绝)。
     */
    @Update(
            """
            UPDATE app_lease
               SET node_id = #{nodeId}, acquired_at = #{now}, last_seen_at = #{now}
             WHERE id = 1
               AND (node_id = '' OR node_id = #{nodeId} OR last_seen_at < #{staleThreshold})
            """)
    int acquireIfAvailable(
            @Param("nodeId") String nodeId,
            @Param("now") OffsetDateTime now,
            @Param("staleThreshold") OffsetDateTime staleThreshold);

    /** heartbeat:只更新自己的 lease(防并发误更新别人的)。返 affectedRows(1=更新了自己的)。 */
    @Update("UPDATE app_lease SET last_seen_at = #{now} WHERE id = 1 AND node_id = #{nodeId}")
    int heartbeat(@Param("nodeId") String nodeId, @Param("now") OffsetDateTime now);

    /** 正常停机 release:清自己的 lease(置空 node_id),让新实例无活跃 lease 直接 acquire。返 affectedRows。 */
    @Update("UPDATE app_lease SET node_id = '' WHERE id = 1 AND node_id = #{nodeId}")
    int release(@Param("nodeId") String nodeId);
}
