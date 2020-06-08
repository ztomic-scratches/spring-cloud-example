package com.example.common.cloud.loadbalancer;

import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.web.reactive.function.client.WebClient;

public class CustomLoadBalancerConfiguration {

	@Bean
	public ServiceInstanceListSupplier discoveryClientServiceInstanceListSupplier(
			ConfigurableApplicationContext context,
			WebClient.Builder webClientBuilder) {
		return ServiceInstanceListSupplier.builder()
				.withBlockingDiscoveryClient()
				.withCaching()
				.withHealthChecks(webClientBuilder.build())
				.build(context);
	}

}