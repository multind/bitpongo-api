package com.multind.bitpongo.auth;

import com.multind.bitpongo.common.api.BusinessException;
import java.util.Base64;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

@Service
@Profile("!test")
public class UserAvatarService {
    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record Avatar(String imageData) {}
    private final JdbcTemplate jdbc;
    public UserAvatarService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(readOnly = true)
    public Avatar get(long userId) {
        active(userId, false);
        return jdbc.query("select image from user_avatar where user_id=?",
                (rs, row) -> avatar(rs.getBytes(1)), userId).stream().findFirst().orElse(new Avatar(null));
    }

    @Transactional
    public Avatar save(long userId, byte[] upload) {
        byte[] image = AvatarImageProcessor.normalize(upload);
        active(userId, true);
        jdbc.update("""
                insert into user_avatar(user_id,image,updated_at) values(?,?,UTC_TIMESTAMP(6))
                on duplicate key update image=?,updated_at=UTC_TIMESTAMP(6)
                """, userId, image, image);
        return avatar(image);
    }

    @Transactional
    public Avatar remove(long userId) {
        active(userId, true);
        jdbc.update("delete from user_avatar where user_id=?", userId);
        return new Avatar(null);
    }

    @Transactional
    public void deleteForUser(long userId) {
        jdbc.queryForList("select id from user where id=? for update", userId);
        jdbc.update("delete from user_avatar where user_id=?", userId);
    }

    private void active(long userId, boolean lock) {
        if (jdbc.queryForList("select id from user where id=? and status='active'" + (lock ? " for update" : ""), userId).isEmpty())
            throw new BusinessException(401, "账号不可用");
    }

    private static Avatar avatar(byte[] image) {
        return new Avatar("data:image/png;base64," + Base64.getEncoder().encodeToString(image));
    }
}
