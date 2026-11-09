package com.nagad.npsb.entity;

import jakarta.persistence.PostPersist;
import jakarta.persistence.PostUpdate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Writes the application log line of an {@link AppLogged} row after the transaction that inserted / updated it
 * has committed (a rolled back change is never logged). Without a Spring transaction it logs at once.
 */
public class AppLogListener {

    @PostPersist
    void inserted(Object row) {
        after(row, true);
    }

    @PostUpdate
    void updated(Object row) {
        after(row, false);
    }

    private static void after(Object row, boolean inserted) {
        if (!(row instanceof AppLogged logged)) return;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    log(logged, inserted);
                }
            });
        } else {
            log(logged, inserted);
        }
    }

    private static void log(AppLogged row, boolean inserted) {
        try {
            row.appLog(inserted);
        } catch (RuntimeException ignored) {
            // the application log must never break a commit
        }
    }
}
