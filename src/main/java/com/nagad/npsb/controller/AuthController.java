package com.nagad.npsb.controller;

import com.nagad.npsb.dto.auth.ChangePasswordRequest;
import com.nagad.npsb.dto.auth.EmailCodeRequest;
import com.nagad.npsb.dto.auth.ForgotPasswordRequest;
import com.nagad.npsb.dto.auth.LoginRequest;
import com.nagad.npsb.dto.auth.ProfileUpdateRequest;
import com.nagad.npsb.dto.auth.RegisterRequest;
import com.nagad.npsb.dto.auth.ResendCodeRequest;
import com.nagad.npsb.dto.auth.ResetPasswordRequest;
import com.nagad.npsb.dto.auth.VerifyCodeRequest;
import com.nagad.npsb.entity.User;
import com.nagad.npsb.security.CurrentUser;
import com.nagad.npsb.service.AuthService;
import com.nagad.npsb.service.auth.ResponseCookies;
import com.nagad.npsb.web.Body;
import com.nagad.npsb.web.RequestValidationException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Sign-in, sessions, My Profile and password recovery (/api/auth). The work is done by {@link AuthService}; this
 * keeps the order of the checks: public endpoints read the body, then AuthService applies the rate limit; the others
 * check the sign-in first, then read the body. Cookies the handler sets are only sent when it succeeds.
 */
@RestController
@Tag(name = "auth")
public class AuthController {
    private final AuthService auth;
    private final CurrentUser currentUser;

    public AuthController(AuthService auth, CurrentUser currentUser) {
        this.auth = auth;
        this.currentUser = currentUser;
    }

    /** Run the handler with the response's cookies held back; they are written only when it returns normally. */
    private static <T> T withCookies(HttpServletResponse response, Function<HttpServletResponse, T> handler) {
        ResponseCookies cookies = new ResponseCookies(response);
        T answer = handler.apply(cookies);
        cookies.send();
        return answer;
    }

    /** An int path parameter, checked after the sign-in (as before): 422 when it is not a number. */
    private static int pathInt(String name, String value) {
        String v = value.strip();
        try {
            return Integer.parseInt(v.startsWith("+") ? v.substring(1) : v);
        } catch (NumberFormatException e) {
            throw new RequestValidationException(List.of(Body.error("int_parsing", List.of("path", name),
                    "Input should be a valid integer, unable to parse string as an integer", value)));
        }
    }

    @GetMapping("/api/auth/settings")
    @Operation(summary = "Auth Settings")
    public Map<String, Object> settings() {
        return auth.authSettings();
    }

    @PostMapping("/api/auth/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Register")
    public Map<String, Object> register(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = RegisterRequest.class)))
            @RequestBody(required = false) String body) {
        return auth.register(RegisterRequest.from(Body.parse(body)));
    }

    @PostMapping("/api/auth/verify-code")
    @Operation(summary = "Step 2 of sign-in: the emailed code is correct -> the user is signed in.")
    public Map<String, Object> verifyCode(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = VerifyCodeRequest.class)))
            @RequestBody(required = false) String body, HttpServletResponse response) {
        VerifyCodeRequest in = VerifyCodeRequest.from(Body.parse(body));
        return withCookies(response, cookies -> auth.verifyCode(in, cookies));
    }

    @PostMapping("/api/auth/resend-code")
    @Operation(summary = "A new sign-in code (only while a sign-in is in progress).")
    public Map<String, Object> resendCode(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = ResendCodeRequest.class)))
            @RequestBody(required = false) String body) {
        return auth.resendCode(ResendCodeRequest.from(Body.parse(body)));
    }

    @PostMapping("/api/auth/login")
    @Operation(summary = "Login")
    public Map<String, Object> login(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = LoginRequest.class)))
            @RequestBody(required = false) String body, HttpServletResponse response) {
        LoginRequest in = LoginRequest.from(Body.parse(body));
        return withCookies(response, cookies -> auth.login(in, cookies));
    }

    @PostMapping("/api/auth/refresh")
    @Operation(summary = "Exchange the refresh cookie for a new access token.")
    public Map<String, Object> refresh(HttpServletResponse response) {
        return withCookies(response, auth::refresh);
    }

    @PostMapping("/api/auth/logout")
    @Operation(summary = "Logout")
    public Map<String, Object> logout(HttpServletResponse response) {
        return withCookies(response, auth::logout);
    }

    @PostMapping("/api/auth/logout-all")
    @Operation(summary = "Sign out on every device.")
    public Map<String, Object> logoutAll(HttpServletResponse response) {
        User user = currentUser.require();
        return withCookies(response, cookies -> auth.logoutAll(user, cookies));
    }

    @GetMapping("/api/auth/me")
    @Operation(summary = "Me")
    public Map<String, Object> me() {
        return AuthService.userToDict(currentUser.require());
    }

    @PutMapping("/api/auth/me")
    @Operation(summary = "Name changes apply at once. A new email only replaces the old one after its code is entered.")
    public Map<String, Object> updateProfile(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = ProfileUpdateRequest.class)))
            @RequestBody(required = false) String body) {
        User user = currentUser.require();
        return auth.updateProfile(user, ProfileUpdateRequest.from(Body.parse(body)));
    }

    @PostMapping("/api/auth/me/confirm-email")
    @Operation(summary = "Confirm Email Change")
    public Map<String, Object> confirmEmailChange(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = EmailCodeRequest.class)))
            @RequestBody(required = false) String body) {
        User user = currentUser.require();
        return auth.confirmEmailChange(user, EmailCodeRequest.from(Body.parse(body)));
    }

    @PostMapping("/api/auth/me/resend-email-code")
    @Operation(summary = "Resend Email Change Code")
    public Map<String, Object> resendEmailChangeCode() {
        return auth.resendEmailChangeCode(currentUser.require());
    }

    @DeleteMapping("/api/auth/me/pending-email")
    @Operation(summary = "Cancel Email Change")
    public Map<String, Object> cancelEmailChange() {
        return auth.cancelEmailChange(currentUser.require());
    }

    @PostMapping("/api/auth/change-password")
    @Operation(summary = "Change Password")
    public Map<String, Object> changePassword(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = ChangePasswordRequest.class)))
            @RequestBody(required = false) String body, HttpServletResponse response) {
        User user = currentUser.require();
        ChangePasswordRequest in = ChangePasswordRequest.from(Body.parse(body));
        return withCookies(response, cookies -> auth.changePassword(user, in, cookies));
    }

    @PostMapping("/api/auth/forgot-password")
    @Operation(summary = "Always gives the same answer, so nobody can find out which emails have an account.")
    public Map<String, Object> forgotPassword(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = ForgotPasswordRequest.class)))
            @RequestBody(required = false) String body) {
        return auth.forgotPassword(ForgotPasswordRequest.from(Body.parse(body)));
    }

    @PostMapping("/api/auth/reset-password")
    @Operation(summary = "Set a new password with the 6-digit code emailed by forgot-password.")
    public Map<String, Object> resetPassword(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = ResetPasswordRequest.class)))
            @RequestBody(required = false) String body) {
        return auth.resetPassword(ResetPasswordRequest.from(Body.parse(body)));
    }

    @GetMapping("/api/auth/sessions")
    @Operation(summary = "Devices/browsers where the user is signed in.")
    public List<Object> listSessions() {
        return auth.listSessions(currentUser.require());
    }

    @DeleteMapping("/api/auth/sessions/{session_id}")
    @Operation(summary = "Revoke Session")
    public Map<String, Object> revokeSession(@PathVariable("session_id") String sessionId) {
        User user = currentUser.require();
        return auth.revokeSession(user, pathInt("session_id", sessionId));
    }

    @GetMapping("/api/auth/activity")
    @Operation(summary = "My Activity")
    public List<Object> myActivity() {
        return auth.myActivity(currentUser.require());
    }
}
