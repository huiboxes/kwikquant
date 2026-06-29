package com.kwikquant.account.infrastructure;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface EncryptionKeyMapper {

    @Select(
            """
            SELECT id, key_version, encrypted_key, active, created_at
            FROM encryption_keys WHERE active = true
            ORDER BY key_version DESC LIMIT 1
            """)
    EncryptionKeyRow findActiveKey();

    @Select(
            """
            SELECT id, key_version, encrypted_key, active, created_at
            FROM encryption_keys WHERE key_version = #{keyVersion}
            """)
    EncryptionKeyRow findByVersion(int keyVersion);

    @Select(
            """
            SELECT id, key_version, encrypted_key, active, created_at
            FROM encryption_keys ORDER BY key_version
            """)
    List<EncryptionKeyRow> findAll();

    @Insert(
            """
            INSERT INTO encryption_keys (key_version, encrypted_key, active)
            VALUES (#{keyVersion}, #{encryptedKey}, #{active})
            """)
    void insert(EncryptionKeyRow row); // id 由 DB 生成，record 不可变故不回填

    @Update("UPDATE encryption_keys SET active = false WHERE active = true")
    int deactivateAll();

    record EncryptionKeyRow(Long id, int keyVersion, String encryptedKey, boolean active, Instant createdAt) {
        public EncryptionKeyRow(int keyVersion, String encryptedKey, boolean active) {
            this(null, keyVersion, encryptedKey, active, null);
        }
    }
}
