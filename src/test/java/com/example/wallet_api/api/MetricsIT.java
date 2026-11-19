package com.example.wallet_api.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.example.wallet_api.support.OracleIntegrationTest;

import tools.jackson.databind.json.JsonMapper;

/**
 * Metricas expostas pelo Actuator (com a API key): o contador de negocio e as metricas HTTP do proprio Spring.
 */
class MetricsIT extends OracleIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void businessCounterAndHttpMetricsAreExposed() throws Exception {
        String created = mvc.perform(post("/wallets")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long walletId = JsonMapper.builder().build().readTree(created).get("id").asLong();
        String uri = "/wallets/" + walletId + "/transactions";
        String credit = "{\"type\":\"CREDIT\",\"amount\":5.00}";

        mvc.perform(post(uri).header("Idempotency-Key", "m1").contentType(MediaType.APPLICATION_JSON).content(credit))
                .andExpect(status().isCreated());
        mvc.perform(post(uri).header("Idempotency-Key", "m1").contentType(MediaType.APPLICATION_JSON).content(credit))
                .andExpect(status().isOk());
        mvc.perform(post(uri).header("Idempotency-Key", "m2").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"DEBIT\",\"amount\":999}"))
                .andExpect(status().isUnprocessableContent());

        mvc.perform(get("/actuator/metrics/wallet.transactions").param("tag", "outcome:replayed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.measurements[0].value").isNumber());
        mvc.perform(get("/actuator/metrics/wallet.transactions").param("tag", "outcome:INSUFFICIENT_FUNDS"))
                .andExpect(status().isOk());
        mvc.perform(get("/actuator/metrics/http.server.requests"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableTags[?(@.tag == 'uri')]").exists());
    }
}
