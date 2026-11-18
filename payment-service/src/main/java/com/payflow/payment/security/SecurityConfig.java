package com.payflow.payment.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

import java.util.List;

/**
 * Who may do what. Roles:
 * <ul>
 *   <li>MAKER   - creates payments</li>
 *   <li>CHECKER - approves or rejects held payments (but never their own: see Payment.approve)</li>
 *   <li>AUDITOR - reads payments and runs reconciliation</li>
 * </ul>
 * Users are in memory with HTTP Basic to keep the demo self-contained. In production this would be
 * an OAuth2 resource server validating JWTs from an identity provider such as Keycloak.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SecurityConfig.DemoUsers.class)
public class SecurityConfig {

    @ConfigurationProperties("payflow.security")
    public record DemoUsers(List<DemoUser> users) {
    }

    public record DemoUser(String username, String password, List<String> roles) {
    }

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http) throws Exception {
        http
                // No cookies or browser sessions: every request carries credentials, so CSRF can't apply.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(Customizer.withDefaults())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/payments").hasRole("MAKER")
                        .requestMatchers(HttpMethod.POST, "/api/payments/*/approve", "/api/payments/*/reject")
                            .hasRole("CHECKER")
                        .requestMatchers(HttpMethod.GET, "/api/payments", "/api/payments/**")
                            .hasAnyRole("MAKER", "CHECKER", "AUDITOR")
                        .requestMatchers("/api/reconciliation/**").hasRole("AUDITOR")
                        .requestMatchers("/error").permitAll()
                        // Anything not listed is refused: new endpoints are closed until someone opens them.
                        .anyRequest().denyAll());
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        // bcrypt by default, and stored hashes say which algorithm made them ("{bcrypt}...").
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    UserDetailsService users(DemoUsers demoUsers, PasswordEncoder encoder) {
        List<UserDetails> users = demoUsers.users().stream()
                .map(u -> User.withUsername(u.username())
                        .password(encoder.encode(u.password()))
                        .roles(u.roles().toArray(String[]::new))
                        .build())
                .toList();
        return new InMemoryUserDetailsManager(users);
    }
}
