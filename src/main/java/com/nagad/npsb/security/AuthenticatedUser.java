package com.nagad.npsb.security;

/** The principal of a signed-in request (the access token's user). */
public record AuthenticatedUser(Integer id, String username, String role) {
}
