package com.multind.zhitoubao.exchange;

import com.multind.zhitoubao.common.api.BusinessException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.multind.zhitoubao.exchange.ExchangeDtos.BalanceView;
import static com.multind.zhitoubao.exchange.ExchangeDtos.ExchangeCheckRequest;
import static com.multind.zhitoubao.exchange.ExchangeDtos.ExchangeUpsertRequest;
import static com.multind.zhitoubao.exchange.ExchangeDtos.ExchangeView;
import static com.multind.zhitoubao.exchange.ExchangeDtos.MinimumAmountRequest;

@Service
public class ExchangeApplicationService {
    private final ExchangeRepository exchanges;
    private final ExchangeGatewayRegistry gateways;
    private final CredentialMasker masker;
    private final Clock clock;

    @Autowired
    public ExchangeApplicationService(
            ExchangeRepository exchanges,
            ExchangeGatewayRegistry gateways,
            CredentialMasker masker) {
        this(exchanges, gateways, masker, Clock.systemUTC());
    }

    ExchangeApplicationService(
            ExchangeRepository exchanges,
            ExchangeGatewayRegistry gateways,
            CredentialMasker masker,
            Clock clock) {
        this.exchanges = exchanges;
        this.gateways = gateways;
        this.masker = masker;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<ExchangeView> list(long userId, int skip, int limit) {
        if (skip < 0 || limit < 1 || limit > 1000) {
            throw new BusinessException(400, "分页参数无效");
        }
        return exchanges.findByUserId(userId).stream()
                .skip(skip)
                .limit(limit)
                .map(this::listView)
                .toList();
    }

    @Transactional(readOnly = true)
    public ExchangeView detail(long userId, long exchangeId) {
        return detailView(owned(userId, exchangeId));
    }

    @Transactional
    public ExchangeView create(long userId, ExchangeUpsertRequest request) {
        ExchangeEntity entity = new ExchangeEntity();
        entity.setUserId(userId);
        entity.setCreatedAt(LocalDateTime.ofInstant(clock.instant(), clock.getZone()));
        apply(entity, request, true);
        return detailView(exchanges.save(entity));
    }

    @Transactional
    public ExchangeView update(long userId, long exchangeId, ExchangeUpsertRequest request) {
        ExchangeEntity entity = owned(userId, exchangeId);
        apply(entity, request, false);
        return detailView(exchanges.save(entity));
    }

    @Transactional
    public void delete(long userId, long exchangeId) {
        exchanges.delete(owned(userId, exchangeId));
    }

    @Transactional(readOnly = true)
    public BalanceView check(long userId, ExchangeCheckRequest request) {
        String code;
        ExchangeCredentials credentials;
        if (request.id() != null) {
            ExchangeEntity entity = owned(userId, request.id());
            code = entity.getExchange();
            credentials = credentials(entity.getAccessKey(), entity.getSecretKey(), entity.getPassword());
        } else {
            code = request.exchange();
            credentials = credentials(request.accessKey(), request.secretKey(), request.password());
        }
        AccountBalance balance = gateways.require(code).verifyCredentials(credentials);
        return new BalanceView(balance.asset(), balance.free(), balance.locked());
    }

    @Transactional(readOnly = true)
    public BigDecimal minimumAmount(long userId, MinimumAmountRequest request) {
        ExchangeEntity entity = owned(userId, request.exchangeId());
        ExchangeGateway gateway = gateways.require(entity.getExchange());
        return request.coins() == null ? BigDecimal.ZERO : request.coins().stream()
                .map(ExchangeApplicationService::symbol)
                .map(gateway::getMarketRules)
                .map(MarketRules::minimumNotional)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private ExchangeEntity owned(long userId, long exchangeId) {
        return exchanges.findByIdAndUserId(exchangeId, userId)
                .orElseThrow(() -> new BusinessException(404, "交易所不存在"));
    }

    private void apply(ExchangeEntity entity, ExchangeUpsertRequest request, boolean creating) {
        if (request.name() != null) entity.setName(request.name());
        if (request.exchange() != null) entity.setExchange(request.exchange().trim().toLowerCase(Locale.ROOT));
        if (request.accessKey() != null && (creating || !masker.isPlaceholder(request.accessKey()))) {
            entity.setAccessKey(request.accessKey());
        }
        if (request.secretKey() != null && (creating || !masker.isPlaceholder(request.secretKey()))) {
            entity.setSecretKey(request.secretKey());
        }
        if (request.password() != null && (creating || !masker.isPlaceholder(request.password()))) {
            entity.setPassword(request.password());
        }
        if (request.status() != null) entity.setStatus(request.status());
        if (creating && (entity.getExchange() == null || entity.getExchange().isBlank())) {
            throw new BusinessException(400, "交易所代码不能为空");
        }
    }

    private ExchangeView listView(ExchangeEntity entity) {
        return view(entity, masker.maskAccessKey(entity.getAccessKey()), null);
    }

    private ExchangeView detailView(ExchangeEntity entity) {
        return view(entity, masker.maskDetail(entity.getAccessKey()), masker.maskDetail(entity.getSecretKey()));
    }

    private static ExchangeView view(ExchangeEntity entity, String accessKey, String secretKey) {
        return new ExchangeView(
                entity.getId(), entity.getName(), entity.getExchange(), accessKey, secretKey,
                entity.getPassword() == null ? null : "*".repeat(entity.getPassword().length()),
                entity.getStatus(), entity.getUserId(), entity.getCreatedAt());
    }

    private static ExchangeCredentials credentials(String accessKey, String secretKey, String password) {
        try {
            return new ExchangeCredentials(accessKey, secretKey, password);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(400, "API Key 和 Secret Key 不能为空");
        }
    }

    private static String symbol(String coin) {
        if (coin == null || coin.isBlank()) {
            throw new BusinessException(400, "币种不能为空");
        }
        String normalized = coin.replace("/", "").trim().toUpperCase(Locale.ROOT);
        return normalized.endsWith("USDT") ? normalized : normalized + "USDT";
    }
}
