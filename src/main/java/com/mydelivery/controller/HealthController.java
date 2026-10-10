package com.mydelivery.controller;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
@RestController
public class HealthController {
    @GetMapping("/api/health") public ResponseEntity<Void> health() { return ResponseEntity.ok().build(); }
}
