
package com.example.chat.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.*;

@RestController
@RequestMapping("/api/files")
public class FileController {

    @Value("${file.upload-dir}")
    private String uploadDir;

    @PostMapping
    public String upload(@RequestParam MultipartFile file) throws Exception {

        Path path = Paths.get(uploadDir + file.getOriginalFilename());
        Files.createDirectories(path.getParent());
        Files.copy(file.getInputStream(), path, StandardCopyOption.REPLACE_EXISTING);

        return "/files/" + file.getOriginalFilename();
    }
}
