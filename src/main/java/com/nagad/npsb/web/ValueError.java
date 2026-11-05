package com.nagad.npsb.web;

/** Raised by a field validator: reported as "Value error, <message>" (pydantic). */
public class ValueError extends RuntimeException {
    public ValueError(String message) {
        super(message);
    }
}
