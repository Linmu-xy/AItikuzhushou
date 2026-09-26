package com.tikuzhushou.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.tikuzhushou.identity.KnowledgeBaseAccessService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class DocumentIntakeLegacyHashTests {
  @TempDir Path tempDir;

  @Test
  void backfillsFingerprintFromOriginalWithoutRemovingIt() throws Exception {
    Path source = tempDir.resolve("legacy.pdf");
    Files.writeString(source, "abc");
    DriverManagerDataSource dataSource = new DriverManagerDataSource(
        "jdbc:h2:mem:legacy_hash_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    jdbc.execute("create table source_documents (id uuid primary key, knowledge_base_id uuid, original_name varchar(255), media_type varchar(100), size_bytes bigint, storage_key varchar(1000), content_sha256 varchar(64), created_at timestamp)");
    UUID documentId = UUID.randomUUID();
    jdbc.update("insert into source_documents values (?,?,?,?,?,?,?,?)", documentId, UUID.randomUUID(),
        "legacy.pdf", "application/pdf", 3L, source.toString(), null, Timestamp.from(Instant.now()));
    DocumentIntakeService intake = new DocumentIntakeService(jdbc,
        mock(KnowledgeBaseAccessService.class), new ObjectStorageService("local", tempDir.toString(), "", "test", "", ""));

    String hash = intake.ensureSha256(documentId);

    assertThat(hash).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    assertThat(jdbc.queryForObject("select content_sha256 from source_documents where id=?", String.class, documentId)).isEqualTo(hash);
    assertThat(Files.readString(source)).isEqualTo("abc");
    assertThat(intake.ensureSha256(documentId)).isEqualTo(hash);
  }
}
