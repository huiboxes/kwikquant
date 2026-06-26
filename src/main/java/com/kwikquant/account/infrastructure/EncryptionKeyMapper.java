package com.kwikquant.account.infrastructure;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface EncryptionKeyMapper {

    @Select(
            """
            SELECT id, key_version, active, created_at
            FROM encryption_keys WHERE active = true
            ORDER BY key_version DESC LIMIT 1
            """)
    EncryptionKeyRow findActiveKey();

    @Select(
            """
            SELECT id, key_version, active, created_at
            FROM encryption_keys WHERE key_version = #{keyVersion}
            """)
    EncryptionKeyRow findByVersion(int keyVersion);

    @Select(
            """
            SELECT id, key_version, active, created_at
            FROM encryption_keys ORDER BY key_version
            """)
    List<EncryptionKeyRow> findAll();

    @Insert(
            """
            INSERT INTO encryption_keys (key_version, active)
            VALUES (#{keyVersion}, #{active})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(EncryptionKeyRow row);

    @Update("UPDATE encryption_keys SET active = false WHERE active = true")
    int deactivateAll();

    record EncryptionKeyRow(Long id, int keyVersion, boolean active, Instant createdAt) {
        public EncryptionKeyRow(int keyVersion, boolean active) {
            this(null, keyVersion, active, null);
        }
    }
}
