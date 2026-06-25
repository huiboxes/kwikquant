package com.kwikquant.account.infrastructure;

import com.kwikquant.account.domain.User;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface UserMapper {

    @Select(
            """
            SELECT id, username, email, password_hash, enabled, created_at, updated_at
            FROM users WHERE id = #{id}
            """)
    User findById(long id);

    @Select(
            """
            SELECT id, username, email, password_hash, enabled, created_at, updated_at
            FROM users WHERE username = #{username}
            """)
    User findByUsername(String username);

    @Select(
            """
            SELECT id, username, email, password_hash, enabled, created_at, updated_at
            FROM users WHERE email = #{email}
            """)
    User findByEmail(String email);

    @Insert(
            """
            INSERT INTO users (username, email, password_hash, enabled)
            VALUES (#{username}, #{email}, #{passwordHash}, #{enabled})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(User user);

    @Update(
            """
            UPDATE users SET password_hash = #{passwordHash}, updated_at = now()
            WHERE id = #{id}
            """)
    int updatePassword(long id, String passwordHash);
}
