package com.nagad.npsb.controller;

import com.nagad.npsb.entity.User;
import com.nagad.npsb.security.CurrentUser;
import com.nagad.npsb.service.ExcelParser;
import com.nagad.npsb.service.UploadService;
import com.nagad.npsb.service.dispatch.FileDownload;
import com.nagad.npsb.web.Downloads;
import com.nagad.npsb.web.Uploads;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Uploaded dispute files: upload &amp; validate, earlier uploads, their rows and their transactions grouped by partner. */
@RestController
@Tag(name = "uploads", description = "Uploaded dispute files")
public class UploadController {
    private final CurrentUser currentUser;
    private final UploadService uploads;

    public UploadController(CurrentUser currentUser, UploadService uploads) {
        this.currentUser = currentUser;
        this.uploads = uploads;
    }

    static ResponseEntity<byte[]> download(FileDownload f) {
        return Downloads.file(f.data(), f.contentType(), f.disposition());
    }

    @GetMapping("/api/upload-template")
    @Operation(summary = "Sample upload file with the required columns.")
    public ResponseEntity<byte[]> downloadUploadTemplate() {
        currentUser.require();
        return download(uploads.uploadTemplate());
    }

    @PostMapping("/api/uploads")
    @Operation(summary = "Upload File")
    public Map<String, Object> uploadFile(@RequestParam(name = "file", required = false) MultipartFile file) throws IOException {
        User user = currentUser.require();
        MultipartFile part = Uploads.require("file", file);
        byte[] content = part.getBytes();
        return uploads.uploadFile(user, part.getOriginalFilename(), content);
    }

    @GetMapping("/api/uploads")
    @Operation(summary = "Earlier uploads, newest first, with how far their emails have got.")
    public List<Object> listUploads() {
        currentUser.require();
        return uploads.listUploads();
    }

    @GetMapping("/api/uploads/{upload_id}/summary")
    @Operation(summary = "An earlier upload, as shown right after uploading it.")
    public Map<String, Object> getUploadSummary(@PathVariable("upload_id") int uploadId) {
        currentUser.require();
        return uploads.getUploadSummary(uploadId);
    }

    @GetMapping("/api/uploads/{upload_id}/rows/{kind}")
    @Operation(summary = "The upload's valid or faulty rows as a new .xlsx.")
    public ResponseEntity<byte[]> downloadUploadRows(@PathVariable("upload_id") int uploadId, @PathVariable("kind") String kind)
            throws ExcelParser.FileValidationError {
        currentUser.require();
        return download(uploads.downloadUploadRows(uploadId, kind));
    }

    @GetMapping("/api/uploads/{upload_id}")
    @Operation(summary = "Transactions grouped by partner (KEY_ORG_NM) and mail type (TXN_DIRECTION).")
    public Map<String, Object> getUploadGroups(@PathVariable("upload_id") int uploadId) {
        currentUser.require();
        return uploads.getUploadGroups(uploadId);
    }
}
