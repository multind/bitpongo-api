package com.multind.bitpongo.content;

import com.multind.bitpongo.common.api.BusinessException;
import com.multind.bitpongo.common.time.UtcDateTimes;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

@Service @Profile("!test")
public class ContentService {
    private final JdbcTemplate jdbc;
    public ContentService(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record Article(String slug,String kind,String title,String summary,String body,Instant publishedAt) {}
    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record Page(List<Article> items,boolean hasMore) {}
    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record Search(long id,String query,Instant searchedAt) {}

    @Transactional(readOnly=true)
    public Page list(String kind,String locale,String query,int page) {
        if(!List.of("HELP","ARTICLE").contains(kind) || page<0 || page>10000)throw invalid();
        String search=normalizeQuery(query,false);
        String pattern="%"+search.replace("!","!!").replace("%","!%").replace("_","!_")+"%";
        var rows=jdbc.query("select slug,kind,title,summary,'' as body,published_at from content_article where kind=? and locale=? and published=true and published_at<=? and (title like ? escape '!' or summary like ? escape '!' or body like ? escape '!') order by published_at desc,slug limit 21 offset ?",
            ContentService::article,kind,locale(locale),UtcDateTimes.toDatabase(Instant.now()),pattern,pattern,pattern,page*20);
        return new Page(rows.stream().limit(20).toList(),rows.size()>20);
    }
    @Transactional(readOnly=true)
    public Article detail(String slug,String locale) {
        return jdbc.query("select * from content_article where slug=? and locale=? and published=true and published_at<=?",ContentService::article,slug,locale(locale),UtcDateTimes.toDatabase(Instant.now()))
            .stream().findFirst().orElseThrow(()->new BusinessException(404,"文章不存在"));
    }
    @Transactional(readOnly=true)
    public List<Search> history(long userId) {
        return jdbc.query("select * from help_search_history where user_id=? order by searched_at desc,id desc limit 20",
            (r,n)->new Search(r.getLong("id"),r.getString("query"),UtcDateTimes.toInstant(r.getTimestamp("searched_at").toLocalDateTime())),userId);
    }
    @Transactional
    public void saveSearch(long userId,String query) {
        // Serialize per-account trimming and avoid creating personal data after account deletion.
        if(jdbc.query("select id from user where id=? and status='active' for update",(r,n)->r.getLong(1),userId).isEmpty())throw new BusinessException(401,"账号不可用");
        jdbc.update("insert into help_search_history(user_id,query,searched_at) values(?,?,?) on duplicate key update searched_at=values(searched_at)",userId,normalizeQuery(query,true),UtcDateTimes.toDatabase(Instant.now()));
        var ids=jdbc.queryForList("select id from help_search_history where user_id=? order by searched_at desc,id desc limit 100 offset 20",Long.class,userId);
        for(Long id:ids)jdbc.update("delete from help_search_history where user_id=? and id=?",userId,id);
    }
    @Transactional
    public void remove(long userId,long id) {
        if(jdbc.update("delete from help_search_history where user_id=? and id=?",userId,id)==0)throw new BusinessException(404,"搜索记录不存在");
    }
    @Transactional
    public void clear(long userId) { jdbc.update("delete from help_search_history where user_id=?",userId); }
    private static Article article(java.sql.ResultSet r,int n)throws java.sql.SQLException {
        return new Article(r.getString("slug"),r.getString("kind"),r.getString("title"),r.getString("summary"),r.getString("body"),UtcDateTimes.toInstant(r.getTimestamp("published_at").toLocalDateTime()));
    }
    private static String locale(String value) {
        String normalized=value==null?"en-us":value.toLowerCase(Locale.ROOT);
        if(!List.of("en-us","zh-cn","zh-tw").contains(normalized))throw invalid();return normalized;
    }
    private static String normalizeQuery(String query,boolean required) {
        String value=query==null?"":query.strip();
        if(value.length()>100 || (required && value.isEmpty()) || value.codePoints().anyMatch(Character::isISOControl))throw invalid();return value;
    }
    private static BusinessException invalid(){return new BusinessException(400,"内容查询参数无效");}
}
