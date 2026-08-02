package com.subh.client;

import com.subh.config.FeignConfig;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.Map;

@FeignClient(name = "user-service", url = "${user-service.url:http://localhost:8081}", configuration = FeignConfig.class)
public interface UserServiceClient {

    @GetMapping("/users/{id}")
    Map<String, Object> getUserById(@PathVariable("id") String id);
}
