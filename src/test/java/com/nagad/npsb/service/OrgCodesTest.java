package com.nagad.npsb.service;

import com.nagad.npsb.entity.Partner;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrgCodesTest {

    @Test
    void leadingZerosDontMatter() {
        assertEquals("103", OrgCodes.normalize("0103"));
        assertEquals("103", OrgCodes.normalize("000103"));
        assertEquals("103", OrgCodes.normalize("103"));
        assertEquals("103", OrgCodes.normalize(" 103 "));
        assertEquals("103", OrgCodes.normalize(103));
        assertEquals("103", OrgCodes.normalize("103.0"));  // an Excel number cell
        assertEquals("22002", OrgCodes.normalize("022002"));
        assertEquals("0", OrgCodes.normalize("000"));
        assertTrue(OrgCodes.same("0103", "103"));
        assertFalse(OrgCodes.same("103", "1030"));
    }

    @Test
    void notACode() {
        assertNull(OrgCodes.normalize(null));
        assertNull(OrgCodes.normalize(""));
        assertNull(OrgCodes.normalize("   "));
        assertNull(OrgCodes.normalize("10A"));
        assertNull(OrgCodes.normalize("-103"));
        assertNull(OrgCodes.normalize("10.3"));
        assertNull(OrgCodes.normalize("1234567890123"));  // more than 12 digits
        assertFalse(OrgCodes.same(null, null));
    }

    @Test
    void caseIdsCarryTheCode() {
        assertEquals("007", OrgCodes.forCaseId("7"));
        assertEquals("103", OrgCodes.forCaseId("0103"));
        assertEquals("22002", OrgCodes.forCaseId("22002"));
        Partner p = new Partner();
        p.setId(4);
        assertEquals("004", CaseIds.partnerPart(p));      // no code yet: the id, as before codes
        p.setOrgCode("103");
        assertEquals("103", CaseIds.partnerPart(p));
        assertEquals("202611001031000", CaseIds.prefix(java.time.LocalDate.of(2026, 11, 1), CaseIds.partnerPart(p), "inward"));
    }
}
