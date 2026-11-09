package com.nagad.npsb.security;

import com.nagad.npsb.config.LiveSettings;
import com.nagad.npsb.config.NpsbProperties;
import com.nagad.npsb.entity.User;
import com.nagad.npsb.util.Crypto;
import com.nagad.npsb.util.Py;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Access tokens: HS256 JWTs in the same format as the Python backend's (python-jose), so sessions stay valid
 * whichever backend issued them.
 */
@Service
public class JwtService {
    private final NpsbProperties properties;
    private final LiveSettings live;

    public JwtService(NpsbProperties properties, LiveSettings live) {
        this.properties = properties;
        this.live = live;
    }

    /** A signed access token for this user (valid for ACCESS_TOKEN_MINUTES). */
    public String createAccessToken(User user) {
        long now = System.currentTimeMillis() / 1000;
        Map<String, Object> payload = Py.map(
                "sub", String.valueOf(user.getId()),
                "username", user.getUsername(),
                "ver", user.getTokenVersion(),
                "type", "access",
                "iss", NpsbProperties.JWT_ISSUER,
                "iat", now,
                "exp", now + live.accessTokenMinutes() * 60L,
                "jti", Crypto.tokenHex(8));
        return Crypto.jwtEncode(payload, properties.effectiveJwtSecret());
    }

    /** The checked claims of a token (signature, issuer, expiry; exp, sub and ver must be present). */
    public Map<String, Object> decode(String token) throws Crypto.JwtError {
        return Crypto.jwtDecode(token, properties.effectiveJwtSecret(), NpsbProperties.JWT_ISSUER, "exp", "sub", "ver");
    }
}
