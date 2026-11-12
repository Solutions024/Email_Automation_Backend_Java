package com.nagad.npsb.config;

import com.nagad.npsb.web.BackgroundTasks;
import com.nagad.npsb.web.CorsFilter;
import com.nagad.npsb.web.RequestLoggingFilter;
import com.nagad.npsb.web.RequestSizeLimitFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewFilter;

/**
 * The servlet filters, in this order (Spring Security's filter chain runs at -100, after them):
 * CORS, the [HTTP] application log line (+ background tasks), the request size limit, and one JPA persistence
 * context per request (opened before Spring Security, so the signed-in user is loaded into it).
 */
@Configuration(proxyBeanMethods = false)
public class WebConfig {

    @Bean
    public FilterRegistrationBean<CorsFilter> corsFilter(NpsbProperties properties) {
        FilterRegistrationBean<CorsFilter> bean = new FilterRegistrationBean<>(new CorsFilter(properties.frontendOrigin()));
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return bean;
    }

    @Bean
    public FilterRegistrationBean<RequestLoggingFilter> requestLoggingFilter(BackgroundTasks backgroundTasks) {
        FilterRegistrationBean<RequestLoggingFilter> bean = new FilterRegistrationBean<>(new RequestLoggingFilter(backgroundTasks));
        bean.setOrder(-200);
        return bean;
    }

    @Bean
    public FilterRegistrationBean<RequestSizeLimitFilter> requestSizeLimitFilter() {
        FilterRegistrationBean<RequestSizeLimitFilter> bean = new FilterRegistrationBean<>(new RequestSizeLimitFilter());
        bean.setOrder(-190);
        return bean;
    }

    /** One persistence context per request (spring.jpa.open-in-view is off: this filter replaces it). */
    @Bean
    public FilterRegistrationBean<OpenEntityManagerInViewFilter> openEntityManagerInViewFilter() {
        FilterRegistrationBean<OpenEntityManagerInViewFilter> bean = new FilterRegistrationBean<>(new OpenEntityManagerInViewFilter());
        bean.setOrder(-150);
        return bean;
    }
}
