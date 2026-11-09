package com.nagad.npsb.repository;

import com.nagad.npsb.entity.InboxIssue;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InboxIssueRepository extends JpaRepository<InboxIssue, Integer> {
}
