package com.nagad.npsb.config;

import com.nagad.npsb.repository.UserRepository;
import com.nagad.npsb.security.JwtAuthenticationFilter;
import com.nagad.npsb.security.CrossSiteRequests;
import com.nagad.npsb.security.JwtService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

import java.nio.charset.StandardCharsets;

/**
 * Spring Security: stateless sign-in with the JWT access token (JwtAuthenticationFilter), no server sessions,
 * CSRF protection that refuses state-changing requests sent from another site (CrossSiteRequests; no CSRF tokens:
 * the API uses bearer tokens and the refresh cookie is SameSite=strict), CORS by CorsFilter.
 *
 * Which endpoints need a signed-in user or an admin is decided per endpoint (CurrentUser.require() /
 * requireAdmin()), so each one keeps the FastAPI backend's exact 401 / 403 messages and the order of its checks.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtService jwt, UserRepository users,
                                                   NpsbProperties properties) throws Exception {
        http
                .csrf(csrf -> csrf
                        .requireCsrfProtectionMatcher(new CrossSiteRequests(properties.frontendOrigin()))
                        .csrfTokenRepository(new CookieCsrfTokenRepository()))  // stateless: no server session
                .exceptionHandling(errors -> errors.accessDeniedHandler(CROSS_SITE_REFUSED))
                .cors(cors -> cors.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .requestCache(cache -> cache.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .addFilterBefore(new JwtAuthenticationFilter(jwt, users), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /** The answer to a refused cross-site request (the only access Spring Security denies here). */
    private static final AccessDeniedHandler CROSS_SITE_REFUSED = (request, response, denied) -> {
        response.setStatus(403);
        response.setContentType("application/json");
        response.getOutputStream().write("{\"detail\":\"Cross-site request refused\"}".getBytes(StandardCharsets.UTF_8));
    };
}
