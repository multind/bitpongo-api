package com.multind.bitpongo.market;

import com.multind.bitpongo.common.time.UtcDateTimes;
import java.time.Instant;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class FearGreedArchive {
    public record Snapshot(List<FearGreedService.Point> points, Instant fetchedAt) {}
    private final JdbcTemplate jdbc;
    public FearGreedArchive(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    @Transactional
    public void save(List<FearGreedService.Point> points, Instant fetchedAt) {
        jdbc.batchUpdate("""
                insert into fear_greed_history(observed_at,value,classification,fetched_at) values(?,?,?,?)
                on duplicate key update value=?,classification=?,fetched_at=?
                """, points, 365, (statement,point)->{
            statement.setObject(1,UtcDateTimes.toDatabase(point.observedAt()));
            statement.setInt(2,point.value()); statement.setString(3,point.classification());
            statement.setObject(4,UtcDateTimes.toDatabase(fetchedAt));
            statement.setInt(5,point.value()); statement.setString(6,point.classification());
            statement.setObject(7,UtcDateTimes.toDatabase(fetchedAt));
        });
    }

    @Transactional(readOnly=true)
    public Snapshot load() {
        var rows=jdbc.query("select observed_at,value,classification,fetched_at from fear_greed_history order by observed_at desc limit 365",
                (rs,row)->new Row(new FearGreedService.Point(rs.getInt("value"),rs.getString("classification"),
                UtcDateTimes.toInstant(rs.getTimestamp("observed_at").toLocalDateTime())),
                UtcDateTimes.toInstant(rs.getTimestamp("fetched_at").toLocalDateTime())));
        return new Snapshot(rows.stream().map(Row::point).toList(),rows.isEmpty()?null:rows.getFirst().fetchedAt());
    }
    private record Row(FearGreedService.Point point, Instant fetchedAt) {}
}
