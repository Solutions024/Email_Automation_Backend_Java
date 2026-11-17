package com.nagad.npsb.service;

import com.nagad.npsb.dto.EmailSettingsRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The rewritten regular expressions answer as before, and in linear time on hostile input. */
class TextPatternsTest {

    @Test
    void emailAddresses() {
        for (String ok : List.of("a@b.c", "first.last@mail.example.com", "x@..b", "x@a..b", "x@a.b.", "ä@ü.de")) {
            assertTrue(EmailSettingsRequest.EMAIL_RE.matcher(ok).matches(), ok);
        }
        for (String bad : List.of("a@b", "a@b.", "a@.b", "@b.c", "a b@c.d", "a@b@c.d", "a@b,c.d", "a@b.c;")) {
            assertFalse(EmailSettingsRequest.EMAIL_RE.matcher(bad).matches(), bad);
        }
    }

    @Test
    @Timeout(5)
    void emailAddressCheckIsFastOnHostileInput() {
        String hostile = "a@" + "b".repeat(50_000) + "@";
        assertFalse(EmailSettingsRequest.EMAIL_RE.matcher(hostile).matches());
        assertTrue(EmailSettingsRequest.EMAIL_RE.matcher("a@" + ".".repeat(50_000)).matches());  // as before: dots count as text
    }

    @Test
    void subjectPrefixesAreRemoved() {
        assertEquals("case 1", Inbox.normSubject("Re: FW: case 1"));
        assertEquals("case 1", Inbox.normSubject("  RE [2] :  Fwd:re:Case   1 "));
        assertEquals("case 1", Inbox.normSubject("AW: SV: WG: Antw: case 1"));
        assertEquals("regarding: x", Inbox.normSubject("Regarding: x"));
        assertEquals("", Inbox.normSubject(null));
        assertEquals("re", Inbox.normSubject("re"));
    }

    @Test
    void hiddenBlocksAreRemovedFromText() {
        assertEquals("a b", Inbox.textOf("<html><head><title>T</title></head><body>a <STYLE>p{}</style>b</body></html>"));
        assertEquals("x y", Inbox.textOf("x<script type=x>alert(1)</SCRIPT>y"));
        assertEquals("keep <styles>", Inbox.withoutHiddenBlocks("keep <styles>"));
        assertEquals("<style>never closed", Inbox.withoutHiddenBlocks("<style>never closed"));
        assertEquals("a b", Inbox.withoutHiddenBlocks("a<head>1</head>b"));
        assertEquals("a   c", Inbox.withoutHiddenBlocks("a <style>x</style> c"));
        assertEquals("1 2 3", Inbox.withoutHiddenBlocks("1<style>a</style>2<Script>b</script >x</script>3"));
    }

    @Test
    @Timeout(5)
    void hiddenBlockRemovalIsFastOnHostileInput() {
        String hostile = "<style ".repeat(100_000);
        assertEquals(hostile, Inbox.withoutHiddenBlocks(hostile));
    }

    @Test
    void answerParagraphs() {
        assertEquals("<p style=\"margin:0 0 12px\">one</p><p style=\"margin:0 0 12px\">two<br/>three</p>",
                AnswerService.textHtml("one\n \n\t\ntwo\nthree"));
    }

    @Test
    void passwordRules() {
        assertEquals(List.of(), AuthService.passwordProblems("Abcdef1!", null, null));
        assertEquals(List.of("a lowercase letter", "a symbol"), AuthService.passwordProblems("ABCDEFG1", null, null));
        assertEquals(List.of("an uppercase letter", "a number"), AuthService.passwordProblems("abcdefg!", null, null));
    }
}
