package com.aiops.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JWT 工具：HS512，密钥从配置读取（禁止硬编码）
 */
@Component
public class JwtUtils {

    private final SecretKey key;
    private final long expireHours;

    public JwtUtils(@Value("${aiops.security.jwt-secret}") String secret,
                    @Value("${aiops.security.jwt-expire-hours:12}") long expireHours) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expireHours = expireHours;
    }

    /** 生成 token，载荷含 userId / username / roles */
    public String createToken(Long userId, String username, List<String> roles) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("username", username);
        claims.put("roles", roles);
        Date now = new Date();
        return Jwts.builder()
                .claims(claims)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expireHours * 3600_000L))
                .signWith(key, Jwts.SIG.HS512)
                .compact();
    }

    /** 解析 token，非法或过期返回 null */
    public LoginUser parseToken(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token).getPayload();
            LoginUser user = new LoginUser();
            user.setUserId(claims.get("userId", Long.class));
            user.setUsername(claims.get("username", String.class));
            user.setRoles(Set.copyOf(claims.get("roles", List.class)));
            return user;
        } catch (Exception e) {
            return null;
        }
    }
}
