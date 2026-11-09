package com.nagad.npsb.repository;

import com.nagad.npsb.entity.EmailReplyAttachment;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailReplyAttachmentRepository extends JpaRepository<EmailReplyAttachment, Integer> {
    /** A message's attached files without their contents (reply.attachments in the Python backend). */
    List<AttachmentInfo> findByReplyIdOrderByIdAsc(Integer replyId);

    /** An attached file without its content. */
    interface AttachmentInfo {
        Integer getId();

        Integer getReplyId();

        String getFilename();

        String getContentType();

        Integer getSize();
    }
}
