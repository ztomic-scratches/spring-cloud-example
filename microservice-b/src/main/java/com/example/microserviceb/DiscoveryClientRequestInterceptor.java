package com.example.microserviceb;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.client.loadbalancer.LoadBalancerUriTools;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.support.HttpRequestWrapper;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryException;
import org.springframework.retry.RetryPolicy;
import org.springframework.retry.backoff.BackOffPolicy;
import org.springframework.retry.backoff.NoBackOffPolicy;
import org.springframework.retry.context.RetryContextSupport;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.util.Assert;

public class DiscoveryClientRequestInterceptor implements ClientHttpRequestInterceptor {

	private static final Logger log = LoggerFactory.getLogger(DiscoveryClientRequestInterceptor.class);

	private final DiscoveryClient discoveryClient;

	public DiscoveryClientRequestInterceptor(DiscoveryClient discoveryClient) {
		this.discoveryClient = discoveryClient;
	}

	@Override
	public ClientHttpResponse intercept(final HttpRequest request, final byte[] body,
			final ClientHttpRequestExecution execution) throws IOException {
		final URI originalUri = request.getURI();
		final String serviceName = originalUri.getHost();
		Assert.state(serviceName != null,"Request URI does not contain a valid hostname: " + originalUri);
		RetryTemplate template = createRetryTemplate(serviceName);
		return template.execute(context -> {
			ServiceRetryPolicy.ServiceRetryContext retryContext = null;
			ServiceInstance serviceInstance = null;
			if (context instanceof ServiceRetryPolicy.ServiceRetryContext) {
				retryContext = (ServiceRetryPolicy.ServiceRetryContext) context;
				serviceInstance = retryContext.getServiceInstance();
				log.debug("Using {} for '{}' request.", serviceInstance, originalUri);
			}
			return execution.execute(new ServiceRequestWrapper(request, serviceInstance), body);
		});
	}

	private RetryTemplate createRetryTemplate(String serviceName) {
		RetryTemplate template = new RetryTemplate();
		BackOffPolicy backOffPolicy = new NoBackOffPolicy();
		template.setBackOffPolicy(backOffPolicy);
		template.setThrowLastExceptionOnExhausted(true);
		template.setRetryPolicy(new ServiceRetryPolicy(discoveryClient, serviceName));
		return template;
	}

	class ServiceRetryPolicy implements RetryPolicy {

		private final DiscoveryClient discoveryClient;
		private final String serviceName;

		ServiceRetryPolicy(DiscoveryClient discoveryClient, String serviceName) {
			this.discoveryClient = discoveryClient;
			this.serviceName = serviceName;
		}

		@Override
		public boolean canRetry(RetryContext context) {
			if (context instanceof ServiceRetryContext) {
				return ((ServiceRetryContext) context).canRetry();
			}
			return false;
		}

		@Override
		public RetryContext open(RetryContext parent) {
			return new ServiceRetryContext(parent);
		}

		@Override
		public void close(RetryContext context) {

		}

		@Override
		public void registerThrowable(RetryContext context, Throwable throwable) {
			if (context instanceof ServiceRetryContext) {
				((ServiceRetryContext) context).registerThrowable(throwable);
			}
		}

		class ServiceRetryContext extends RetryContextSupport {

			private final List<ServiceInstance> serviceInstances;

			private ServiceInstance serviceInstance;

			public ServiceRetryContext(RetryContext parent) {
				super(parent);
				this.serviceInstances = new ArrayList<>(discoveryClient.getInstances(serviceName));
				if (this.serviceInstances.isEmpty()) {
					registerThrowable(new RetryException("Not found any service instance of service: " + serviceName));
				}
			}

			public boolean canRetry() {
				return !serviceInstances.isEmpty();
			}

			public ServiceInstance getServiceInstance() {
				if (serviceInstance == null) {
					Optional<ServiceInstance> serviceInstanceOptional = serviceInstances.stream().findAny();
					if (serviceInstanceOptional.isPresent()) {
						serviceInstances.remove(serviceInstanceOptional.get());
						serviceInstance = serviceInstanceOptional.get();
					}
				}
				return serviceInstance;
			}

			public void setServiceInstance(ServiceInstance serviceInstance) {
				this.serviceInstance = serviceInstance;
			}

			@Override
			public void registerThrowable(Throwable throwable) {
				if (serviceInstance != null) {
					log.info("{} failed with {}", serviceInstance, throwable.getMessage());
				} else {
					log.info("Failed with {}", throwable.getMessage());
				}
				super.registerThrowable(throwable);
				setServiceInstance(null);
			}
		}
	}

	public class ServiceRequestWrapper extends HttpRequestWrapper {

		private final ServiceInstance instance;

		public ServiceRequestWrapper(HttpRequest request, ServiceInstance instance) {
			super(request);
			this.instance = instance;
		}

		@Override
		public URI getURI() {
			URI uri = LoadBalancerUriTools.reconstructURI(this.instance, getRequest().getURI());
			return uri;
		}

	}


}
