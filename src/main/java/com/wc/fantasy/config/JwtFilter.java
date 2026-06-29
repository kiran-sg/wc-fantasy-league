package com.wc.fantasy.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.wc.fantasy.model.AppUser;
import com.wc.fantasy.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class JwtFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserRepository userRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            try {
                if (jwtService.isValid(token)) {
                    String username = jwtService.extractUsername(token);
                    AppUser user = userRepository.findByUsername(username).orElse(null);
                    List<GrantedAuthority> authorities = new ArrayList<>();
                    if (user != null && Boolean.TRUE.equals(user.getIsAdmin())) {
                        authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
                        if ("superadmin".equals(username)) {
                            authorities.add(new SimpleGrantedAuthority("ROLE_SUPERADMIN"));
                        }
                    }
                    var auth = new UsernamePasswordAuthenticationToken(username, null, authorities);
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            } catch (Exception ignored) {
                // Invalid token - let the request proceed without auth
            }
        }
        chain.doFilter(request, response);
    }
}
