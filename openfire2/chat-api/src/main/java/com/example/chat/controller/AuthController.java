
package com.example.chat.controller;

import com.example.chat.security.JwtUtil;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final JwtUtil jwt;

    public AuthController(JwtUtil jwt) {
        this.jwt = jwt;
    }

    @PostMapping("/login")
    public Map<String,String> login(@RequestBody Map<String,String> body) {
        String token = jwt.generateToken(body.get("username"));
        return Map.of("token", token);
    }
}
