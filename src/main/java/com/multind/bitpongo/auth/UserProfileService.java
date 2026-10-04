package com.multind.bitpongo.auth;

import com.multind.bitpongo.common.api.BusinessException;
import java.util.Locale;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

@Service
@Profile("!test")
public class UserProfileService {
    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record Profile(String name, String country, String introduction, String upDownColor) {}
    private static final Set<String> COUNTRIES = Set.of(Locale.getISOCountries());
    private final JdbcTemplate jdbc;

    public UserProfileService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(readOnly = true)
    public Profile get(long userId) {
        return jdbc.query("""
                select coalesce(u.name,'') name,coalesce(p.country,'') country,coalesce(p.introduction,'') introduction,
                coalesce(p.up_down_color,'GREEN_UP') up_down_color
                from user u left join user_profile_preferences p on p.user_id=u.id
                where u.id=? and u.status='active'
                """, (rs,i)->new Profile(rs.getString("name"),rs.getString("country"),rs.getString("introduction"),rs.getString("up_down_color")),userId)
                .stream().findFirst().orElseThrow(()->new BusinessException(401,"账号不可用"));
    }

    @Transactional
    public Profile save(long userId, Profile request) {
        if(request==null || request.name()==null || request.country()==null || request.introduction()==null) invalid();
        String name=request.name().strip();
        String country=request.country().strip().toUpperCase(Locale.ROOT);
        String introduction=request.introduction().strip();
        if(name.isBlank() || name.codePointCount(0,name.length())>50 || name.codePoints().anyMatch(Character::isISOControl)
                || introduction.length()>500 || introduction.codePoints().anyMatch(c->Character.isISOControl(c) && c!='\n' && c!='\r')
                || (!country.isEmpty() && !COUNTRIES.contains(country))
                || !("GREEN_UP".equals(request.upDownColor()) || "RED_UP".equals(request.upDownColor()))) invalid();
        if(jdbc.queryForList("select id from user where id=? and status='active' for update",userId).isEmpty())
            throw new BusinessException(401,"账号不可用");
        jdbc.update("update user set name=? where id=?",name,userId);
        jdbc.update("""
                insert into user_profile_preferences(user_id,country,introduction,up_down_color) values(?,?,?,?)
                on duplicate key update country=?,introduction=?,up_down_color=?
                """,userId,country,introduction,request.upDownColor(),country,introduction,request.upDownColor());
        return new Profile(name,country,introduction,request.upDownColor());
    }

    private static void invalid() { throw new BusinessException(400,"资料字段无效"); }

    @Transactional
    public void deleteForUser(long userId) {
        jdbc.queryForList("select id from user where id=? for update", userId);
        jdbc.update("delete from user_profile_preferences where user_id=?",userId);
    }
}
