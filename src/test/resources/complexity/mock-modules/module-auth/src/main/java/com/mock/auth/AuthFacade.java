package com.mock.auth;

import java.util.List;

/** Kimlik — çok sayıda metod + izin matrisi (orta/yüksek). */
public class AuthFacade {

    public boolean login(String user, String pass) {
        return user != null && pass != null && pass.length() >= 8;
    }

    public boolean logout(String sessionId) {
        return sessionId != null;
    }

    public boolean hasRole(String sessionId, String role) {
        if (sessionId == null || role == null) {
            return false;
        }
        return role.equals("ADMIN") || role.equals("USER");
    }

    public String maskEmail(String email) {
        if (email == null || email.indexOf('@') < 0) {
            return "***";
        }
        return email.substring(0, 1) + "***@" + email.substring(email.indexOf('@') + 1);
    }

    public int passwordStrength(String pass) {
        if (pass == null) {
            return 0;
        }
        int score = 0;
        if (pass.length() >= 8) {
            score++;
        }
        if (pass.length() >= 12) {
            score++;
        }
        return score;
    }

    public boolean isSessionExpired(long expiresAt, long now) {
        return now >= expiresAt;
    }

    public boolean authorizeAction(String role, String resource, String action, List scopes, boolean mfaVerified) {
        if (role == null || resource == null || action == null) {
            return false;
        }
        if ("ADMIN".equals(role)) {
            return true;
        }
        if ("USER".equals(role)) {
            if ("READ".equals(action)) {
                return resource.startsWith("public.") || (scopes != null && scopes.size() > 0);
            }
            if ("WRITE".equals(action)) {
                if (!mfaVerified) {
                    return false;
                }
                if (resource.startsWith("own.")) {
                    return true;
                }
                if (scopes != null) {
                    for (int i = 0; i < scopes.size(); i++) {
                        Object s = scopes.get(i);
                        if (s != null && resource.equals(s.toString())) {
                            return true;
                        }
                    }
                }
            }
        }
        if ("AUDITOR".equals(role)) {
            return "READ".equals(action) && resource.indexOf("audit") >= 0;
        }
        return false;
    }
}
