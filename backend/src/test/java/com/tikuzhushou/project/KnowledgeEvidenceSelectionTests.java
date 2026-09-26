package com.tikuzhushou.project;

import com.tikuzhushou.retrieval.RetrievalService;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KnowledgeEvidenceSelectionTests {
  @Test
  void spreadsQuestionsAcrossKnowledgeChunksAndVariants() {
    UUID documentId = UUID.randomUUID();
    List<RetrievalService.Hit> chunks = IntStream.range(0, 20)
        .mapToObj(index -> new RetrievalService.Hit(UUID.randomUUID(), documentId, index,
            "知识库正文" + index, "第" + index + "页", 0)).toList();

    RetrievalService.Hit first = ExamProjectVariantGenerationService.selectKnowledgeHit(chunks, 1, 1, 1);
    assertSame(first, ExamProjectVariantGenerationService.selectKnowledgeHit(chunks, 1, 1, 1));
    assertNotEquals(first.chunkId(), ExamProjectVariantGenerationService.selectKnowledgeHit(chunks, 1, 2, 1).chunkId());
    assertNotEquals(first.chunkId(), ExamProjectVariantGenerationService.selectKnowledgeHit(chunks, 2, 1, 1).chunkId());
  }

  @Test
  void refusesAnEmptyKnowledgeBase() {
    assertThrows(IllegalArgumentException.class,
        () -> ExamProjectVariantGenerationService.selectKnowledgeHit(List.of(), 1, 1, 1));
  }
}
