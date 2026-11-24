package com.ziwei.zw.rag.controller;

import com.ziwei.zw.config.ZwProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerMapping;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * MinerU 解析出的图片等资产：{@code GET /api/assets/{documentId}/{assetName}}。
 *
 * <p>图片落在本地磁盘（{@code app.rag.mineru.asset-dir}），浏览器无法直接访问，
 * 必须有这个 HTTP 出口，Markdown 里的 {@code <img>} 才能显示。
 *
 * <p>安全：路径归一化后必须仍在 asset-dir 内（防目录穿越）；图片 URL 含随机 documentId，
 * 且聊天页面本身就是登录后才可用的，这里放行是为了让 {@code <img>} 标签不带 token 也能加载。
 */
@RestController
@RequiredArgsConstructor
public class AssetController {

    private final ZwProperties props;

    @GetMapping("/api/assets/**")
    public ResponseEntity<FileSystemResource> serve(HttpServletRequest request) {
        String full = (String) request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
        String rel = (full == null ? "" : full).replaceFirst("^/api/assets/", "");
        if (rel.isBlank()) {
            return ResponseEntity.notFound().build();
        }
        // 按文件名反查：解析器把图片写回 Markdown 时只有相对路径 images/xxx.jpg，
        // 而实际落盘目录是 {随机UUID}/images/xxx.jpg，这里按文件名兜底找一次。
        if (!rel.contains("/") && rel.contains(".")) {
            Path found = findByFileName(rel);
            if (found != null) {
                return ok(found);
            }
        }
        Path base = Path.of(props.getRag().getMineru().getAssetDir()).toAbsolutePath().normalize();
        Path target = base.resolve(rel).normalize();
        if (!target.startsWith(base) || !Files.isRegularFile(target)) {
            return ResponseEntity.notFound().build();
        }
        return ok(target);
    }

    /** 在资产目录下按文件名（内容哈希，天然唯一）递归查找 */
    private Path findByFileName(String fileName) {
        Path base = Path.of(props.getRag().getMineru().getAssetDir()).toAbsolutePath().normalize();
        if (!Files.isDirectory(base)) {
            return null;
        }
        try (var stream = Files.walk(base, 6)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals(fileName))
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private ResponseEntity<FileSystemResource> ok(Path target) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(mediaType(target.getFileName().toString())))
                .body(new FileSystemResource(target));
    }

    private String mediaType(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".svg")) return "image/svg+xml";
        if (n.endsWith(".pdf")) return "application/pdf";
        return "application/octet-stream";
    }
}
