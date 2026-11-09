package com.nagad.npsb.config;

import com.nagad.npsb.repository.UserRepository;
import com.nagad.npsb.security.JwtAuthenticationFilter;
import com.nagad.npsb.security.JwtService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security: stateless sign-in with the JWT access token (JwtAuthenticationFilter), no server sessions,
 * no CSRF tokens (the API uses bearer tokens; the refresh cookie is SameSite=strict), CORS by CorsFilter.
 *
 * Which endpoints need a signed-in user or an admin is decided per endpoint (CurrentUser.require() /
 * requireAdmin()), so each one keeps the FastAPI backend's exact 401 / 403 messages and the order of its checks.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtService jwt, UserRepository users) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
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
}
