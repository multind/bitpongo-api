package com.multind.bitpongo.market;

import com.multind.bitpongo.common.api.BusinessException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class AlternativeFearGreedClient implements FearGreedClient {
    private final String baseUrl;
    private final ObjectMapper json;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    public AlternativeFearGreedClient(@Value("${FEAR_GREED_API_BASE_URL:https://api.alternative.me}") String baseUrl,ObjectMapper json) {
        this.baseUrl=baseUrl.replaceAll("/+$","");this.json=json;
    }
    @Override public List<FearGreedService.Point> history() {
        try {
            var request=HttpRequest.newBuilder(URI.create(baseUrl+"/fng/?limit=365&format=json")).timeout(Duration.ofSeconds(10)).GET().build();
            var response=http.send(request,HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(),100001));
            if(response.statusCode()!=200) throw new BusinessException(503,"指数服务暂时不可用");
            if(response.body().length>100000) throw new BusinessException(502,"指数数据格式无效");
            var root=json.readTree(response.body());
            if(root==null || !root.isObject() || !root.path("data").isArray()) throw new BusinessException(502,"指数数据格式无效");
            var error=root.path("metadata").get("error");
            if(error!=null && !error.isNull()) throw new BusinessException(503,"指数服务暂时不可用");
            var result=new ArrayList<FearGreedService.Point>();
            for(var item:root.path("data")) {
                if(!item.path("value").isTextual() || !item.path("timestamp").isTextual() || !item.path("value_classification").isTextual()) throw new BusinessException(502,"指数数据格式无效");
                try {
                    result.add(new FearGreedService.Point(Integer.parseInt(item.get("value").asText()),item.get("value_classification").asText(),Instant.ofEpochSecond(Long.parseLong(item.get("timestamp").asText()))));
                } catch(RuntimeException e) { throw new BusinessException(502,"指数数据格式无效"); }
            }
            return List.copyOf(result);
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();throw new BusinessException(503,"指数请求已中断");
        } catch(BusinessException e) { throw e; }
        catch(Exception e) { throw new BusinessException(503,"指数服务暂时不可用"); }
    }
}
