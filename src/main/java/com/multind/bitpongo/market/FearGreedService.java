package com.multind.bitpongo.market;

import com.multind.bitpongo.common.api.BusinessException;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

@Service
public class FearGreedService {
    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record Point(int value, String classification, Instant observedAt) {}
    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record History(List<Point> points, Instant fetchedAt, boolean stale, String sourceUrl) {}
    private static final Set<String> CLASSES=Set.of("Extreme Fear","Fear","Neutral","Greed","Extreme Greed");
    private final FearGreedClient client;
    private final Clock clock;
    private List<Point> cached;
    private Instant fetchedAt;
    private Instant refreshAt=Instant.MIN;
    private boolean failed;

    @Autowired public FearGreedService(FearGreedClient client) { this(client,Clock.systemUTC()); }
    FearGreedService(FearGreedClient client,Clock clock) { this.client=client;this.clock=clock; }

    public synchronized History history(int limit) {
        if(limit<1 || limit>365) throw new BusinessException(400,"指数数据条数必须为 1–365");
        Instant now=clock.instant();
        if(!now.isBefore(refreshAt)) {
            try {
                List<Point> result=client.history();
                if(result==null || result.isEmpty()) throw new BusinessException(503,"指数服务暂时不可用");
                if(result.size()>365 || result.stream().anyMatch(p->p==null || p.value()<0 || p.value()>100 || !CLASSES.contains(p.classification()==null?"":p.classification()) || p.observedAt()==null || p.observedAt().isAfter(now.plusSeconds(300))))
                    throw new BusinessException(502,"指数数据格式无效");
                var sorted=result.stream().sorted(Comparator.comparing(Point::observedAt).reversed()).toList();
                if(sorted.stream().map(Point::observedAt).distinct().count()!=sorted.size()) throw new BusinessException(502,"指数数据格式无效");
                cached=List.copyOf(sorted);fetchedAt=now;refreshAt=now.plusSeconds(300);failed=false;
            } catch(RuntimeException error) {
                if(cached==null) {
                    if(error instanceof BusinessException business) throw business;
                    throw new BusinessException(503,"指数服务暂时不可用");
                }
                failed=true;refreshAt=now.plusSeconds(60);
            }
        }
        boolean stale=failed || cached.getFirst().observedAt().isBefore(now.minusSeconds(172800));
        return new History(cached.stream().limit(limit).toList(),fetchedAt,stale,"https://alternative.me/crypto/fear-and-greed-index/");
    }

    public Point latestFresh() {
        var history=history(1);
        if(history.stale()) throw new BusinessException(503,"指数数据已过期，暂停生成信号");
        return history.points().getFirst();
    }
}
