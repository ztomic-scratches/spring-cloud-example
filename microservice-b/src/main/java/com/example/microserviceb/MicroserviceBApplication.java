package com.example.microserviceb;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.cloud.client.loadbalancer.reactive.LoadBalancerProperties;
import org.springframework.cloud.loadbalancer.core.DelegatingServiceInstanceListSupplier;
import org.springframework.cloud.loadbalancer.core.DiscoveryClientServiceInstanceListSupplier;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.cloud.netflix.eureka.EnableEurekaClient;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

@SpringBootApplication
@EnableEurekaClient
@EnableRetry
public class MicroserviceBApplication {

	@Bean("loadBalancedRestTemplate")
	@LoadBalanced
	public RestTemplate loadBalancedRestTemplate(RestTemplateBuilder restTemplateBuilder) {
		return restTemplateBuilder.build();
	}

	@Bean
	public ServiceInstanceListSupplier discoveryClientServiceInstanceListSupplier(
			DiscoveryClient discoveryClient, Environment environment,
			LoadBalancerProperties loadBalancerProperties,
			ApplicationContext context,
			RestTemplateBuilder restTemplateBuilder) {
		DiscoveryClientServiceInstanceListSupplier firstDelegate = new DiscoveryClientServiceInstanceListSupplier(
				discoveryClient, environment);
		HealthCheckServiceInstanceListSupplier delegate = new HealthCheckServiceInstanceListSupplier(firstDelegate,
				loadBalancerProperties.getHealthCheck(), restTemplateBuilder.build());
		// TODO: With this HTTP request is blocked
		/*ObjectProvider<LoadBalancerCacheManager> cacheManagerProvider = context
				.getBeanProvider(LoadBalancerCacheManager.class);
		if (cacheManagerProvider.getIfAvailable() != null) {
			return new CachingServiceInstanceListSupplier(delegate,
					cacheManagerProvider.getIfAvailable());
		}*/
		return delegate;
	}


	/**
	 * A {@link ServiceInstanceListSupplier} implementation that verifies whether the
	 * instances are alive and only returns the healthy one, unless there are none. Uses
	 * {@link RestTemplate} to ping the <code>health</code> endpoint of the instances.
	 *
	 * @author Olga Maciaszek-Sharma
	 * @author Roman Matiushchenko
	 * @since 2.2.0
	 */
	public static class HealthCheckServiceInstanceListSupplier
			extends DelegatingServiceInstanceListSupplier
			implements InitializingBean, DisposableBean {

		private static final Log LOG = LogFactory
				.getLog(HealthCheckServiceInstanceListSupplier.class);

		private final LoadBalancerProperties.HealthCheck healthCheck;

		private final RestTemplate restTemplate;

		private final String defaultHealthCheckPath;

		private final Flux<List<ServiceInstance>> aliveInstancesReplay;

		private Disposable healthCheckDisposable;

		public HealthCheckServiceInstanceListSupplier(ServiceInstanceListSupplier delegate,
				LoadBalancerProperties.HealthCheck healthCheck, RestTemplate restTemplate) {
			super(delegate);
			this.healthCheck = healthCheck;
			defaultHealthCheckPath = healthCheck.getPath().getOrDefault("default",
					"/actuator/health");
			this.restTemplate = restTemplate;
			aliveInstancesReplay = Flux.defer(delegate)
					.delaySubscription(Duration.ofMillis(healthCheck.getInitialDelay()))
					.switchMap(serviceInstances -> healthCheckFlux(serviceInstances).map(
							alive -> Collections.unmodifiableList(new ArrayList<>(alive))))
					.replay(1).refCount(1);
		}

		@Override
		public void afterPropertiesSet() {
			Disposable healthCheckDisposable = this.healthCheckDisposable;
			if (healthCheckDisposable != null) {
				healthCheckDisposable.dispose();
			}
			this.healthCheckDisposable = aliveInstancesReplay.subscribe();
		}

		protected Flux<List<ServiceInstance>> healthCheckFlux(
				List<ServiceInstance> instances) {
			return Flux.defer(() -> {
				List<Mono<ServiceInstance>> checks = new ArrayList<>(instances.size());
				for (ServiceInstance instance : instances) {
					Mono<ServiceInstance> alive = isAlive(instance).onErrorResume(error -> {
						if (LOG.isDebugEnabled()) {
							LOG.debug(String.format(
									"Exception occurred during health check of the instance for service %s: %s",
									instance.getServiceId(), instance.getUri()), error);
						}
						return Mono.empty();
					}).timeout(healthCheck.getInterval(), Mono.defer(() -> {
						if (LOG.isDebugEnabled()) {
							LOG.debug(String.format(
									"The instance for service %s: %s did not respond for %s during health check",
									instance.getServiceId(), instance.getUri(),
									healthCheck.getInterval()));
						}
						return Mono.empty();
					})).handle((isHealthy, sink) -> {
						LOG.info(String.format("The instance for service %s: %s is %s", instance.getServiceId(), instance.getInstanceId(), isHealthy ? "UP" : "DOWN"));
						if (isHealthy) {
							sink.next(instance);
						}
					});

					checks.add(alive);
				}
				List<ServiceInstance> result = new ArrayList<>();
				return Flux.merge(checks).map(alive -> {
					result.add(alive);
					return result;
				}).defaultIfEmpty(result);
			}).repeatWhen(restart -> restart.delayElements(healthCheck.getInterval()));
		}

		@Override
		public Flux<List<ServiceInstance>> get() {
			return aliveInstancesReplay;
		}

		protected Mono<Boolean> isAlive(ServiceInstance serviceInstance) {
			String healthCheckPropertyValue = healthCheck.getPath()
					.get(serviceInstance.getServiceId());
			String healthCheckPath = healthCheckPropertyValue != null
					? healthCheckPropertyValue : defaultHealthCheckPath;
			return Mono.fromSupplier(() -> {
				try {
					return restTemplate.getForEntity(UriComponentsBuilder.fromUri(serviceInstance.getUri())
							.path(healthCheckPath).build().toUri(), Void.class).getStatusCodeValue() == HttpStatus.OK.value();
				} catch (RestClientException e) {
					return false;
				}
			});
		}

		@Override
		public void destroy() {
			Disposable healthCheckDisposable = this.healthCheckDisposable;
			if (healthCheckDisposable != null) {
				healthCheckDisposable.dispose();
			}
		}

	}


	@RestController
	class SampleController {

		private final RestTemplate loadBalancedRestTemplate;

		private final String instanceId;

		SampleController(@LoadBalanced RestTemplate loadBalancedRestTemplate, @Value("${spring.application.instance-id}") String instanceId) {
			this.loadBalancedRestTemplate = loadBalancedRestTemplate;
			this.instanceId = instanceId;
		}

		@GetMapping("/hello")
		public String hello() {
			return "Hello from " + instanceId + "@" + LocalDateTime.now();
		}

		@GetMapping("/{microservice}")
		public String microservice(@PathVariable("microservice") String microservice) {
			return loadBalancedRestTemplate.getForObject("http://" + microservice + "/hello", String.class);
		}

	}

	public static void main(String[] args) {
		SpringApplication.run(MicroserviceBApplication.class, args);
	}

}
