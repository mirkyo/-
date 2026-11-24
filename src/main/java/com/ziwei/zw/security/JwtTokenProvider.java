package com.ziwei.zw.security;

import com.ziwei.zw.config.ZwProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 签发与校验。
 */
@Slf4j
@Component
public class JwtTokenProvider {

    private static final String CLAIM_USER_ID = "uid";
    private static final String CLAIM_TENANT_ID = "tid";
    private static final String CLAIM_ROLE = "role";

    private final SecretKey key;
    private final long expireMillis;

    public JwtTokenProvider(ZwProperties props) {
        this.key = Keys.hmacShaKeyFor(props.getJwt().getSecret().getBytes(StandardCharsets.UTF_8));
        this.expireMillis = props.getJwt().getExpireHours() * 3600_000L;
    }

    public String create(Long userId, Long tenantId, String username, String role) {
        Date now = new Date();
        return Jwts.builder()
                .subject(username)
                .claim(CLAIM_USER_ID, userId)
                .claim(CLAIM_TENANT_ID, tenantId)
                .claim(CLAIM_ROLE, role)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expireMillis))
                .signWith(key)
                .compact();
    }

    /** 解析并校验；非法或过期返回 null（由过滤器决定怎么处理）。 */
    public LoginUser parse(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return new LoginUser(
                    claims.get(CLAIM_TENANT_ID, Number.class) == null
                            ? null : claims.get(CLAIM_TENANT_ID, Number.class).longValue(),
                    claims.get(CLAIM_USER_ID, Number.class) == null
                            ? null : claims.get(CLAIM_USER_ID, Number.class).longValue(),
                    claims.getSubject(),
                    claims.get(CLAIM_ROLE, String.class));
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("[jwt] token 解析失败: {}", e.getMessage());
            return null;
        }
    }

    public long getExpireMillis() {
        return expireMillis;
    }
}
