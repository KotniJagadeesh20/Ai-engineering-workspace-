package com.aiengineering.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Dev-focused CORS setup so a plain static HTML file (e.g. the test harness
 * in dev-tools/) can call this API from a browser, which normally has a
 * different origin (e.g. http://localhost:5500) than the API itself
 * (http://localhost:8080).
 *
 * app.cors-allowed-origins defaults to "*" for local development ease.
 * TIGHTEN THIS before any real deployment - restrict to your actual
 * frontend's origin(s), comma-separated.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Value("${app.cors-allowed-origins:*}")
    private String allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
            .allowedOriginPatterns(allowedOrigins.split(","))
            .allowedMethods("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS")
            .allowedHeaders("*")
            .allowCredentials(false); // we use Bearer tokens, not cookies - no credentials needed
    }
}
