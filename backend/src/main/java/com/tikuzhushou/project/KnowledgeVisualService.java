package com.tikuzhushou.project;

import com.tikuzhushou.document.DocumentIntakeService;
import com.tikuzhushou.document.DocumentParsingService;
import com.tikuzhushou.document.ObjectStorageService;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Reads the original visual material; OCR text is never substituted for a diagram. */
@Service
public class KnowledgeVisualService {
  private final ExamProjectService projects;
  private final DocumentIntakeService documents;
  private final DocumentParsingService parsing;
  private final ObjectStorageService storage;
  private final JdbcTemplate jdbc;

  public KnowledgeVisualService(ExamProjectService projects, DocumentIntakeService documents,
      DocumentParsingService parsing, ObjectStorageService storage, JdbcTemplate jdbc) {
    this.projects = projects;
    this.documents = documents;
    this.parsing = parsing;
    this.storage = storage;
    this.jdbc = jdbc;
  }

  public int pageCount(UUID documentId) {
    DocumentIntakeService.DocumentReceipt source = documents.get(documentId);
    if (source.mediaType().startsWith("image/")) return 1;
    if (!"application/pdf".equals(source.mediaType())) return 0;
    Integer count = jdbc.queryForObject("select count(*) from document_pages where document_id=? and active=true", Integer.class, documentId);
    return count == null ? 0 : count;
  }

  /** Coordinates are percentages of the rendered image, not screen pixels. */
  public byte[] page(UUID projectId, UUID documentId, int page, int x, int y, int width, int height) {
    ExamProjectService.ProjectView project = projects.get(projectId);
    if (project.sources().stream().noneMatch(source -> source.enabled()
        && "DOCUMENT".equals(source.sourceType()) && documentId.equals(source.sourceId()))) {
      throw new IllegalArgumentException("图像不属于当前项目的已选资料");
    }
    return page(documentId, page, x, y, width, height);
  }

  public byte[] page(UUID documentId, int page, int x, int y, int width, int height) {
    DocumentIntakeService.DocumentReceipt source = documents.get(documentId);
    if (page < 1 || page > pageCount(documentId)) throw new IllegalArgumentException("原图页码超出范围");
    byte[] original;
    if ("application/pdf".equals(source.mediaType())) {
      original = parsing.renderPage(documentId, page, 240);
    } else if (source.mediaType().startsWith("image/")) {
      Path path = null;
      try {
        path = storage.materialize(source.storagePath(), source.name());
        original = Files.readAllBytes(path);
      } catch (Exception error) {
        throw new IllegalStateException("原始图片读取失败", error);
      } finally {
        if (path != null && source.storagePath().startsWith("minio://")) {
          try { storage.cleanupMaterialized(path); } catch (Exception ignored) { }
        }
      }
    } else {
      throw new IllegalArgumentException("该资料没有可直接查看的原图");
    }
    if (x == 0 && y == 0 && width == 100 && height == 100 && "application/pdf".equals(source.mediaType())) return original;
    try {
      BufferedImage image = ImageIO.read(new java.io.ByteArrayInputStream(original));
      if (image == null) throw new IllegalArgumentException("图片格式无法读取");
      int left = Math.max(0, Math.min(99, x));
      int top = Math.max(0, Math.min(99, y));
      int right = Math.max(left + 1, Math.min(100, x + width));
      int bottom = Math.max(top + 1, Math.min(100, y + height));
      int px = left * image.getWidth() / 100;
      int py = top * image.getHeight() / 100;
      int pw = Math.max(1, Math.min(image.getWidth() - px, right * image.getWidth() / 100 - px));
      int ph = Math.max(1, Math.min(image.getHeight() - py, bottom * image.getHeight() / 100 - py));
      try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
        ImageIO.write(image.getSubimage(px, py, pw, ph), "png", output);
        return output.toByteArray();
      }
    } catch (Exception error) {
      throw new IllegalStateException("原图裁切失败", error);
    }
  }

  public List<Integer> pages(UUID documentId) {
    int count = pageCount(documentId);
    return java.util.stream.IntStream.rangeClosed(1, count).boxed().toList();
  }
}
