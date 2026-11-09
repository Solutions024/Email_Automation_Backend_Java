package com.nagad.npsb.repository;

import com.nagad.npsb.entity.Upload;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UploadRepository extends JpaRepository<Upload, Integer>, UploadFileContent {
}
