package com.opayque.api.identity.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/demo")
public class DemoController {

    @GetMapping("/me")
    public ResponseEntity<String> secureEndpoint() {
        return ResponseEntity.ok("If you see this, the Fortress gate is open for you.");
    }
}