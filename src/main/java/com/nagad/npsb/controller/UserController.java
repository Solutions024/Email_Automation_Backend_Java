package com.nagad.npsb.controller;

import com.nagad.npsb.dto.auth.UserCreateRequest;
import com.nagad.npsb.dto.auth.UserUpdateRequest;
import com.nagad.npsb.entity.User;
import com.nagad.npsb.service.AuthService;
import com.nagad.npsb.service.auth.UserAdminService;
import com.nagad.npsb.web.Body;
import com.nagad.npsb.web.QueryParams;
import com.nagad.npsb.web.RequestValidationException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** User management and the security audit log. The built-in admin only (other admins can't manage accounts). */
@RestController
@Tag(name = "users")
public class UserController {
    private final AuthService auth;
    private final UserAdminService userAdmin;

    public UserController(AuthService auth, UserAdminService userAdmin) {
        this.auth = auth;
        this.userAdmin = userAdmin;
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

    @GetMapping("/api/users")
    @Operation(summary = "List Users")
    public List<Object> listUsers() {
        auth.requireBuiltInAdmin();
        return userAdmin.listUsers();
    }

    /** Create an account for someone. They sign in with their password plus a code emailed to them. */
    @PostMapping("/api/users")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create an account for someone. An admin may create another admin without the admin code.")
    public Map<String, Object> createUser(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = UserCreateRequest.class)))
            @RequestBody(required = false) String body) {
        User me = auth.requireBuiltInAdmin();
        UserCreateRequest in = UserCreateRequest.from(Body.parse(body));
        return userAdmin.createUser(me, in);
    }

    @PatchMapping("/api/users/{user_id}")
    @Operation(summary = "Update User")
    public Map<String, Object> updateUser(
            @PathVariable("user_id") String userId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = UserUpdateRequest.class)))
            @RequestBody(required = false) String body) {
        User me = auth.requireBuiltInAdmin();
        int id = pathInt("user_id", userId);
        UserUpdateRequest in = UserUpdateRequest.from(Body.parse(body));
        return userAdmin.updateUser(me, id, in);
    }

    @GetMapping("/api/users/events")
    @Operation(summary = "Audit Log")
    public List<Object> auditLog(@RequestParam(name = "limit", required = false) String limit) {
        auth.requireBuiltInAdmin();
        int n = Math.max(1, Math.min(QueryParams.integer("limit", limit, 100), 500));
        return userAdmin.auditLog(n);
    }
}
