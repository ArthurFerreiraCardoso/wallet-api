package com.example.wallet_api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.example.wallet_api.support.OracleIntegrationTest;

class WalletApiApplicationIT extends OracleIntegrationTest {

    @Autowired
    JdbcClient jdbcClient;

    @Test
    void applicationConnectsAsWalletAppInFreepdb1() {
        String user = jdbcClient.sql("SELECT SYS_CONTEXT('USERENV', 'SESSION_USER') FROM dual").query(String.class).single();
        String container = jdbcClient.sql("SELECT SYS_CONTEXT('USERENV', 'CON_NAME') FROM dual").query(String.class).single();

        assertThat(user).isEqualTo("WALLET_APP");
        assertThat(container).isEqualTo("FREEPDB1");
    }
}
