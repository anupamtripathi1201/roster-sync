package com.moveinsync.rostersync.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.rostersync.dto.Dtos.ApiError;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.io.IOException;


@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, ObjectMapper mapper, JwtService jwt) throws Exception {
        AuthenticationEntryPoint unauthenticated = (req, res, ex) ->
                write(res, mapper, 401, "UNAUTHENTICATED", "Log in and send the token as 'Authorization: Bearer ...'");
        AccessDeniedHandler forbidden = (req, res, ex) ->
                write(res, mapper, 403, "FORBIDDEN", "Not allowed for this role");

        http.csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/actuator/**").hasRole("OPERATOR")
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/me").authenticated()
                        .requestMatchers("/api/v1/hr/**").hasRole("HR_SYSTEM")
                        .requestMatchers(HttpMethod.GET, "/api/v1/employees/me").hasRole("EMPLOYEE")
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/employees/*/transport").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/v1/employees/*").hasAnyRole("ADMIN", "OPERATOR")
                        .requestMatchers("/api/v1/ops/**").hasRole("OPERATOR")
                        .anyRequest().denyAll())
                .exceptionHandling(e -> e.authenticationEntryPoint(unauthenticated).accessDeniedHandler(forbidden))
                .addFilterBefore(new JwtAuthenticationFilter(jwt), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }


    @Bean
    public AuthenticationManager authenticationManager(UserAccountService users, PasswordEncoder encoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(users);
        provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }

    private static void write(HttpServletResponse res, ObjectMapper mapper, int status, String code, String msg)
            throws IOException {
        res.setStatus(status);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(res.getOutputStream(), new ApiError(code, msg, MDC.get("traceId"), null));
    }
}
