package com.nagad.npsb.service.auth;

import com.nagad.npsb.web.ApiException;

/**
 * An error that is answered only AFTER the changes made so far have been committed. Many sign-in handlers record a
 * failed attempt (a counter, an audit event), commit, and then answer 400 / 401 / 403 / 423 / 429, so lock-outs,
 * wrong-code counters and the security log keep the attempt. AuthService's transaction blocks commit when they catch
 * it and throw it afterwards; it is answered like any ApiException. Any other exception rolls the block back.
 */
public class CommitThenRaise extends ApiException {

    public CommitThenRaise(int status, Object detail) {
        super(status, detail);
    }

    /** The same error (status, detail and headers), answered after the commit. */
    public static CommitThenRaise of(ApiException error) {
        if (error instanceof CommitThenRaise same) return same;
        CommitThenRaise out = new CommitThenRaise(error.status(), error.detail());
        error.headers().forEach(out::header);
        return out;
    }
}
