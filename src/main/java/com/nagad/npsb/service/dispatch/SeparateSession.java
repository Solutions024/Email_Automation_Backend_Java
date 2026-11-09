package com.nagad.npsb.service.dispatch;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.function.Supplier;

/**
 * Work in a persistence context of its own (the old "try (Db db = Db.open()) { ... }"): a new EntityManager is bound
 * to the thread for the duration of the work, like the request's own (OpenEntityManagerInViewFilter), and the
 * request's context (if any) is put back afterwards. Repositories and TransactionTemplate blocks inside the work use
 * the new context, so loaded rows stay managed across its transactions, and a failed transaction there never
 * affects the request's rows. Used by the automatic follow-up job (also from "Run now") and for marking replies read.
 */
@Component
public class SeparateSession {
    private final EntityManagerFactory emf;

    public SeparateSession(EntityManagerFactory emf) {
        this.emf = emf;
    }

    public <T> T run(Supplier<T> work) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("A separate session can't be opened inside a transaction");
        }
        Object previous = TransactionSynchronizationManager.unbindResourceIfPossible(emf);
        EntityManager em = emf.createEntityManager();
        TransactionSynchronizationManager.bindResource(emf, new EntityManagerHolder(em));
        try {
            return work.get();
        } finally {
            TransactionSynchronizationManager.unbindResourceIfPossible(emf);
            EntityManagerFactoryUtils.closeEntityManager(em);
            if (previous != null) TransactionSynchronizationManager.bindResource(emf, previous);
        }
    }
}
