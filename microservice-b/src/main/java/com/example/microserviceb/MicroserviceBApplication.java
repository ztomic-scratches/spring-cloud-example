package com.example.microserviceb;

import java.time.LocalDateTime;

import com.example.common.cloud.annotation.EnableLoadBalanced;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.eureka.EnableEurekaClient;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

@SpringBootApplication
@EnableEurekaClient
@EnableRetry
@EnableLoadBalanced
public class MicroserviceBApplication {

	@RestController
	class SampleController {

		private final RestTemplate restTemplate;
		private final String instanceId;

		SampleController(@Qualifier("loadBalancedRestTemplate") RestTemplate loadBalancedRestTemplate, @Value("${spring.application.instance-id}") String instanceId) {
			this.restTemplate = loadBalancedRestTemplate;
			this.instanceId = instanceId;
		}

		@GetMapping("/hello")
		public String hello() {
			return "Hello from " + instanceId + "@" + LocalDateTime.now();
		}

		@GetMapping("/{microservice}")
		public String microservice(@PathVariable("microservice") String microservice) {
			return restTemplate.getForObject("http://" + microservice + "/hello", String.class);
		}

	}

	public static void main(String[] args) {
		SpringApplication.run(MicroserviceBApplication.class, args);
	}

}
