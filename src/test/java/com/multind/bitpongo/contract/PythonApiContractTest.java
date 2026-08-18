package com.multind.bitpongo.contract;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PythonApiContractTest {
    @Test
    void matrixCoversAllTwentyOneRestEndpointsAndBackgroundContracts() throws Exception {
        String matrix = Files.readString(Path.of("docs/python-java-contract-matrix.md"));
        List<String> endpoints = List.of(
                "GET /", "GET /health", "POST /api/users/login", "POST /api/users/register",
                "GET /api/users/profile", "POST /api/users/v1/login", "DELETE /api/users/account",
                "POST /api/users/ding",
                "GET /api/users/notices", "GET /api/exchanges/list", "GET /api/exchanges/{exchange_id}",
                "POST /api/exchanges/create", "PUT /api/exchanges/{exchange_id}",
                "DELETE /api/exchanges/{exchange_id}", "POST /api/exchanges/check",
                "POST /api/exchanges/minimumAmount", "POST /api/strategies/create",
                "GET /api/strategies/list/active", "GET /api/plans/list/active",
                "GET /api/plans/{plan_id}", "GET /api/plans/{plan_id}/{plan_status}");
        assertThat(endpoints).hasSize(21).allMatch(matrix::contains);
        assertThat(matrix).contains("/api/ws/price", "PlanPurchaseJob", "AssetSnapshotJob");
    }
}
