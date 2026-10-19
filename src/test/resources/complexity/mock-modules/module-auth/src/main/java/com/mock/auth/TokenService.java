package com.mock.auth;

public class TokenService {

    public String issueToken(String subject) {
        if (subject == null) {
            return "anon";
        }
        return "tok-" + subject.hashCode();
    }

    public boolean validateToken(String token) {
        return token != null && token.startsWith("tok-");
    }
}
