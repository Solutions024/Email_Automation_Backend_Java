package com.nagad.npsb.dto;

import com.nagad.npsb.util.Py;
import com.nagad.npsb.web.Body;
import com.nagad.npsb.web.ValueError;
import io.swagger.v3.oas.annotations.media.Schema;

/** An admin's free-text remarks on a sent email. */
@Schema(description = "Remarks on a sent email (at most 500 characters; empty removes them).")
public record RemarksRequest(String remarks) {

    public static RemarksRequest from(Body b) {
        String remarks = b.check("remarks", b.str("remarks"), v -> {
            v = Py.strip(v);
            if (v.length() > 500) throw new ValueError("Remarks can have at most 500 characters");
            return v;
        });
        b.done();
        return new RemarksRequest(remarks);
    }
}
