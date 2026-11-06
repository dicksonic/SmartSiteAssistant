package com.reg.assistant.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;

import java.io.IOException;

/** Web层配置：CORS + 允许已建平台iframe嵌入的安全头 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${app.frame-ancestors:*}")
    private String frameAncestors;

    /** 跨域：预研期放开，生产按平台域名收敛 */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }

    /**
     * iframe嵌入支持：默认 Spring Security 类头会拒绝被嵌入。
     * 用 CSP frame-ancestors 声明可嵌入的平台域名（比 X-Frame-Options 更灵活）。
     */
    @Bean
    public FilterRegistrationBean<OncePerRequestFilter> frameAncestorsFilter() {
        FilterRegistrationBean<OncePerRequestFilter> bean = new FilterRegistrationBean<>();
        bean.setFilter(new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                            FilterChain chain) throws ServletException, IOException {
                String fa = "*".equals(frameAncestors) ? "*" : frameAncestors.replace(",", " ");
                response.setHeader("Content-Security-Policy", "frame-ancestors " + fa);
                chain.doFilter(request, response);
            }
        });
        bean.addUrlPatterns("/*");
        return bean;
    }
}
