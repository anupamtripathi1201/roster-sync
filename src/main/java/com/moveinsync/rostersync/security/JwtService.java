package com.moveinsync.rostersync.security;

import com.moveinsync.rostersync.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

@Service
public class JwtService {
    private static final String TENANT = "tid";
    private static final String ROLE = "role";
    private static final String EMPLOYEE = "eid";

    private final SecretKey key;
    private final long ttlSeconds;
    private final Clock clock;

    public JwtService(AppProperties props, Clock clock) {
        byte[] secret = props.jwtSecret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            throw new IllegalStateException("rostersync.jwt-secret must be at least 32 bytes");
        }
        this.key = Keys.hmacShaKeyFor(secret);
        this.ttlSeconds = props.tokenTtl().toSeconds();
        this.clock = clock;


    }

    public long ttlSeconds(){
        return ttlSeconds;
    }

    public String issue(CurrentUser user) {
        Instant now = clock.instant();
        var builder = Jwts.builder()
                .issuer("roster-sync")
                .subject(user.username())
                .claim(TENANT, user.tenantId())
                .claim(ROLE, user.role().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ttlSeconds)));
        if (user.employeeId() != null) {
            builder.claim(EMPLOYEE, user.employeeId());
        }
        return builder.signWith(key, Jwts.SIG.HS256).compact();   // fixed algorithm, whatever the key length
    }

    public Optional<CurrentUser> verify(String token) {
        try {
            Claims c = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            return Optional.of(new CurrentUser(c.getSubject(), null, CurrentUser.Role.valueOf(c.get(ROLE, String.class)),
                    c.get(TENANT, String.class), c.get(EMPLOYEE, String.class)));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }






}
