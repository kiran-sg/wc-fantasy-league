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

    @Value("${admin.password}")
    private String adminPassword;

    @GetMapping("/me")
    public ResponseEntity<?> me(jakarta.servlet.http.HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return ResponseEntity.status(401).body(Map.of("error", "No token"));
        }
        String username = jwtService.extractUsername(header.substring(7));
        AppUser user = userRepo.findByUsername(username).orElse(null);
        if (user == null) {
            return ResponseEntity.status(401).body(Map.of("error", "User not found"));
        }
        return ResponseEntity.ok(Map.of(
                "userId", user.getId(),
                "username", user.getUsername(),
                "isAdmin", Boolean.TRUE.equals(user.getIsAdmin())
        ));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        AppUser user = userRepo.findByUsername(username).orElse(null);
        if (user == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid username. Please contact the admin."));
        }
        boolean isAdmin = Boolean.TRUE.equals(user.getIsAdmin());
        if (isAdmin) {
            String password = body.get("password");
            // No password supplied yet — tell the client to show the password field
            if (password == null || password.isBlank()) {
                return ResponseEntity.status(200).body(Map.of("requiresPassword", true));
            }
            String expected = "superadmin".equals(username) ? superadminPassword : adminPassword;
            if (!expected.equals(password)) {
                return ResponseEntity.status(401).body(Map.of("error", "Invalid password."));
            }
        }
        String token = jwtService.generateToken(username);
        return ResponseEntity.ok(Map.of(
                "token", token,
                "userId", user.getId(),
                "username", user.getUsername(),
                "isAdmin", isAdmin
        ));
    }
}
