package com.wc.fantasy.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Service
public class JwtService {

    // Captured once when this JVM started — all tokens issued before this moment are stale.
    private static final long SERVER_START = System.currentTimeMillis();

    @Value("${jwt.secret:fantasy-league-super-secret-key-that-is-long-enough-256bit}")
    private String secret;

    @Value("${jwt.expiration:86400000}")
    private long expiration;

    private SecretKey key() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String generateToken(String username) {
        return Jwts.builder()
                .subject(username)
                .claim("svr", SERVER_START)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expiration))
                .signWith(key())
                .compact();
    }

    public String extractUsername(String token) {
        return Jwts.parser().verifyWith(key()).build()
                .parseSignedClaims(token).getPayload().getSubject();
    }

    public boolean isValid(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key()).build()
                    .parseSignedClaims(token).getPayload();
            Long svrClaim = claims.get("svr", Long.class);
            // Reject tokens issued against a previous server instance
            if (svrClaim == null || svrClaim != SERVER_START) return false;
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
