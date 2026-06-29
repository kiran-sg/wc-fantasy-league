package com.wc.fantasy.controller;

import com.wc.fantasy.config.JwtService;
import com.wc.fantasy.model.AppUser;
import com.wc.fantasy.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserRepository userRepo;
    private final JwtService jwtService;

    @Value("${superadmin.password}")
    private String superadminPassword;

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        AppUser user = userRepo.findByUsername(username).orElse(null);
        if (user == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid username. Please contact the admin."));
        }
        if ("superadmin".equals(username)) {
            String password = body.get("password");
            if (password == null || !superadminPassword.equals(password)) {
                return ResponseEntity.status(401).body(Map.of("error", "Invalid password."));
            }
        }
        String token = jwtService.generateToken(username);
        return ResponseEntity.ok(Map.of(
                "token", token,
                "userId", user.getId(),
                "username", user.getUsername(),
                "isAdmin", Boolean.TRUE.equals(user.getIsAdmin())
        ));
    }
}
