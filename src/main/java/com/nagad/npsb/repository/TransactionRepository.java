package com.nagad.npsb.repository;

import com.nagad.npsb.entity.Transaction;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransactionRepository extends JpaRepository<Transaction, Integer> {
    /** An upload's valid rows, in file order (upload.transactions in the Python backend). */
    List<Transaction> findByUploadIdOrderByIdAsc(Integer uploadId);
}
