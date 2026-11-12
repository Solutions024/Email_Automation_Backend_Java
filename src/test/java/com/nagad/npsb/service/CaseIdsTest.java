package com.nagad.npsb.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaseIdsTest {

    @Test
    void prefixIsYearMonthPartnerAndDirection() {
        assertEquals("202611000031000", CaseIds.prefix(LocalDate.of(2026, 11, 9), "003", "inward"));
        assertEquals("202601001252000", CaseIds.prefix(LocalDate.of(2026, 1, 31), "125", "outward"));
        assertEquals("2", CaseIds.direction("outward_reminder"));  // old reminder emails count as outward
    }

    @Test
    void hiddenCaseIdGoesInsideTheBodyWrapper() {
        String html = CaseIds.withHidden("<div><p>Dear Concern,</p></div>\n", "202611000031000001");
        assertTrue(html.startsWith("<div><p>Dear Concern,</p><div style="));
        assertTrue(html.endsWith(CaseIds.LABEL + " 202611000031000001</div></div>\n"));
        assertEquals("<p>Hi</p>" + CaseIds.hiddenHtml("1"), CaseIds.withHidden("<p>Hi</p>", "1"));  // no wrapper: appended
        assertEquals("<div>x</div>", CaseIds.withHidden("<div>x</div>", null));                 // no case ID: unchanged
        assertNull(CaseIds.withHidden(null, "202611000031000001"));
    }

    @Test
    void caseIdIsFoundInAQuotedReply() {
        String quoted = "<p>We checked it.</p><blockquote>" + CaseIds.withHidden("<div>Dear Concern</div>", "202611000031000007")
                + "</blockquote>";
        assertEquals("202611000031000007", CaseIds.find(quoted));
        // a mail client may break the label and the number apart with tags
        assertEquals("202611000031000007", CaseIds.find("NPSB Case ID:</span> <b>202611000031000007</b>"));
        assertNull(CaseIds.find("<p>No case here: 202611000031000007</p>"));
        assertNull(CaseIds.find(null));
    }
}
