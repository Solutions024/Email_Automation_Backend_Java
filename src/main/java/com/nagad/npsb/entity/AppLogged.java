package com.nagad.npsb.entity;

/** A row that writes a line to the application log once its INSERT (inserted) or UPDATE was committed. */
public interface AppLogged {
    void appLog(boolean inserted);
}
