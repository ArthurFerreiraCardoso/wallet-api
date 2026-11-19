package com.example.wallet_api.support;

import java.security.SecureRandom;

/**
 * Usuarios criados por db/init. As senhas sao geradas a cada execucao dos testes.
 */
public enum DbUser {

    OWNER("WALLET_OWNER"),
    APP("WALLET_APP"),
    AUDIT("WALLET_AUDIT");

    private static final String PASSWORD_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";

    private final String username;
    private final String password;

    DbUser(String username) {
        this.username = username;
        this.password = randomPassword();
    }

    public String username() {
        return username;
    }

    public String password() {
        return password;
    }

    String passwordEnvVar() {
        return username + "_PASSWORD";
    }

    private static String randomPassword() {
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder("T");
        for (int i = 0; i < 20; i++) {
            sb.append(PASSWORD_CHARS.charAt(random.nextInt(PASSWORD_CHARS.length())));
        }
        return sb.toString();
    }
}
