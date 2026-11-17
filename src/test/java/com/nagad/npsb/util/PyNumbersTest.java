package com.nagad.npsb.util;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PyNumbersTest {

    /** Py.exact gives the double's exact binary value, digit for digit (what Python's Decimal(float) prints). */
    @Test
    void exactIsTheDoublesExactValue() {
        assertEquals("0.1000000000000000055511151231257827021181583404541015625", Py.exact(0.1).toString());
        assertEquals("2.67499999999999982236431605997495353221893310546875", Py.exact(2.675).toString());
        assertEquals("-1.5", Py.exact(-1.5).toString());
        assertEquals("0", Py.exact(0.0).toString());
        assertEquals("1152921504606846976", Py.exact(Math.pow(2, 60)).toPlainString());
        assertEquals("123456789012345", Py.exact(123456789012345.0).toPlainString());
        BigDecimal tiny = Py.exact(Double.MIN_VALUE);  // the smallest subnormal: 2^-1074, about 4.94E-324
        assertEquals(1, tiny.signum());
        assertEquals(0, BigDecimal.ONE.compareTo(tiny.multiply(new BigDecimal(java.math.BigInteger.TWO.pow(1074)))));
        assertThrows(NumberFormatException.class, () -> Py.exact(Double.NaN));
    }

    /** Rounding the exact value: 2.675 is really 2.67499..., so it rounds down, as in Python. */
    @Test
    void moneyRoundsTheExactValue() {
        assertEquals("2.67", Py.money(2.675));
        assertEquals("1,234,567.89", Py.money(1234567.885));
        assertEquals("0.12", Py.money(0.125));
    }

    @Test
    void betweenIsTheElapsedTime() {
        LocalDateTime a = LocalDateTime.of(2026, 3, 31, 23, 59, 30), b = LocalDateTime.of(2026, 4, 1, 0, 1, 0);
        assertEquals(Duration.ofSeconds(90), Py.between(a, b));
        assertEquals(Duration.ofSeconds(-90), Py.between(b, a));
    }
}
