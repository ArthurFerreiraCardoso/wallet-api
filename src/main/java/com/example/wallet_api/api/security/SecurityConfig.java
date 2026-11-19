package com.example.wallet_api.api.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

import jakarta.servlet.DispatcherType;
import tools.jackson.databind.json.JsonMapper;

/**
 * API stateless protegida por API key. Sem sessao, sem cookie e, por isso, sem CSRF: nenhuma credencial e enviada
 * automaticamente pelo navegador. Os headers de seguranca padrao do Spring (nosniff, no-store...) continuam ativos.
 *
 * <p>Abertos sem chave: o health (para orquestrador/monitoracao, sem detalhes) e a documentacao OpenAPI, que
 * descreve o contrato e nao expoe dados. Em producao a documentacao poderia ficar atras da mesma chave.
 */
@Configuration
@EnableConfigurationProperties(ApiKeyProperties.class)
public class SecurityConfig {

    private static final String[] PUBLIC_PATHS = {
            "/actuator/health", "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**"
    };

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ApiKeyProperties properties, JsonMapper jsonMapper) throws Exception {
        ProblemAuthenticationEntryPoint problemEntryPoint = new ProblemAuthenticationEntryPoint(jsonMapper);
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Dispatch interno de erro do container: a requisicao original ja passou pela autorizacao.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(new ApiKeyAuthenticationFilter(properties.apiKey()), AnonymousAuthenticationFilter.class)
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(problemEntryPoint)
                        .accessDeniedHandler(problemEntryPoint))
                .build();
    }
}
