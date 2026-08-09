package com.multind.zhitoubao.scheduler;

import com.multind.zhitoubao.exchange.*;
import com.multind.zhitoubao.plan.PlanRepository;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnBean(OrderIntentRepository.class)
public class OrderReconciliationService {
    private static final Logger log = LoggerFactory.getLogger(OrderReconciliationService.class);
    private final OrderIntentRepository intents;
    private final PlanRepository plans;
    private final ExchangeRepository exchanges;
    private final ExchangeGatewayRegistry gateways;
    private final OrderPersistenceService persistence;

    public OrderReconciliationService(
            OrderIntentRepository intents, PlanRepository plans, ExchangeRepository exchanges,
            ExchangeGatewayRegistry gateways, OrderPersistenceService persistence) {
        this.intents = intents; this.plans = plans; this.exchanges = exchanges;
        this.gateways = gateways; this.persistence = persistence;
    }

    @Scheduled(fixedDelayString = "${zhitoubao.orders.reconciliation-delay:60s}")
    public void reconcilePending() {
        intents.findByStatusOrderByCreatedAtAsc("PENDING_RECONCILIATION").forEach(intent -> {
            try {
                plans.findById(intent.getPlanId()).flatMap(plan ->
                        exchanges.findByIdAndUserId(plan.getExchangeId(), intent.getUserId()))
                        .ifPresentOrElse(exchange -> reconcile(intent, exchange),
                                () -> persistence.mark(intent, "NOT_FOUND"));
            } catch (RuntimeException exception) {
                log.error("订单对账失败，intentId={}", intent.getId(), exception);
            }
        });
    }

    private void reconcile(OrderIntentEntity intent, ExchangeEntity exchange) {
        ExchangeGateway gateway = gateways.require(exchange.getExchange());
        ExchangeCredentials credentials = new ExchangeCredentials(
                exchange.getAccessKey(), exchange.getSecretKey(), exchange.getPassword());
        Optional<OrderResult> result = gateway.findOrder(credentials, intent.getSymbol(), intent.getClientOrderId());
        result.ifPresentOrElse(value -> persistence.confirm(intent, value),
                () -> persistence.mark(intent, "NOT_FOUND"));
    }
}
