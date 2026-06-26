package com.wc.fantasy.config;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;

import java.util.List;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtFilter jwtFilter;

    @Bean
    public FilterRegistrationBean<Filter> corsFilter() {
        FilterRegistrationBean<Filter> bean = new FilterRegistrationBean<>((req, res, chain) -> {
            HttpServletResponse response = (HttpServletResponse) res;
            HttpServletRequest  request  = (HttpServletRequest)  req;
            response.setHeader("Access-Control-Allow-Origin",  "*");
            response.setHeader("Access-Control-Allow-Methods", "GET,POST,PUT,PATCH,DELETE,OPTIONS");
            response.setHeader("Access-Control-Allow-Headers", "*");
            response.setHeader("Access-Control-Max-Age",       "3600");
            if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
                response.setStatus(HttpServletResponse.SC_OK);
                return;
            }
            chain.doFilter(req, res);
        });
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        bean.addUrlPatterns("/*");
        return bean;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .cors(cors -> cors.configurationSource(r -> {
                    var c = new CorsConfiguration();
                    c.setAllowedOriginPatterns(List.of("*"));
                    c.setAllowedMethods(List.of("GET","POST","PUT","PATCH","DELETE","OPTIONS"));
                    c.setAllowedHeaders(List.of("*"));
                    c.setExposedHeaders(List.of("Authorization"));
                    c.setAllowCredentials(false);
                    c.setMaxAge(3600L);
                    return c;
                }))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers("/api/admin/**").permitAll()
                        .requestMatchers("/api/sync/**").permitAll()
                        .requestMatchers("/h2-console/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/matches/**", "/api/teams/**", "/api/players/**", "/api/leaderboard/**", "/api/round-config/**").permitAll()
                        .requestMatchers(HttpMethod.PUT, "/api/round-config/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/round-config/**").permitAll()
                        .requestMatchers("/api/team/**").authenticated()
                        .anyRequest().authenticated()
                )
                .headers(h -> h.frameOptions(f -> f.disable()))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
