package com.example.common.cloud.loadbalanced;

import com.example.common.cloud.discovery.rest.DiscoveryClientRequestInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class LoadBalancedConfiguration {

	private static final Logger log = LoggerFactory.getLogger(LoadBalancedConfiguration.class);


	@Configuration
	@ConditionalOnClass(WebClient.class)
	class DefaultLoadBalancedConfiguration {

		@LoadBalanced
		@Bean("loadBalancedRestTemplate")
		public RestTemplate loadBalancedRestTemplate(RestTemplateBuilder restTemplateBuilder) {
			return restTemplateBuilder.build();
		}

	}

	@Configuration
	@ConditionalOnMissingClass("org.springframework.web.reactive.function.client.WebClient")
	class InterceptorLoadBalancedConfiguration {

		@Bean("loadBalancedRestTemplate")
		public RestTemplate loadBalancedRestTemplate(RestTemplateBuilder restTemplateBuilder, DiscoveryClient discoveryClient) {
			log.info("No org.springframework.web.reactive.function.client.WebClient in classpath. Creating 'loadBalancedRestTemplate' with interceptor.");
			return restTemplateBuilder
					.interceptors(new DiscoveryClientRequestInterceptor(discoveryClient))
					.build();
		}

	}

}
