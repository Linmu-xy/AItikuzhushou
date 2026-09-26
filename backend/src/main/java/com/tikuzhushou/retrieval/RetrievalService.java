package com.tikuzhushou.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tikuzhushou.identity.KnowledgeBaseAccessService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Hybrid retrieval: lexical evidence dominates the local hash embedding, pgvector provides recall. */
@Service
public class RetrievalService {
  private final JdbcTemplate jdbc;
  private final EmbeddingService embeddings;
  private final ObjectMapper json;
  private final KnowledgeBaseAccessService access;
  private final PostgresVectorStore vectors;

  public RetrievalService(JdbcTemplate jdbc, EmbeddingService embeddings, ObjectMapper json,
      KnowledgeBaseAccessService access, PostgresVectorStore vectors) {
    this.jdbc = jdbc;
    this.embeddings = embeddings;
    this.json = json;
    this.access = access;
    this.vectors = vectors;
  }

  public List<Hit> search(UUID knowledgeBaseId, String query, int topK) {
    access.assertKnowledgeBase(knowledgeBaseId);
    if (query == null || query.isBlank()) throw new IllegalArgumentException("检索关键词不能为空");
    int limit = Math.max(1, Math.min(topK, 20));
    float[] vector = embeddings.embed(query);
    Map<UUID, Double> vectorScores = new HashMap<>();
    for (Hit hit : vectors.search(knowledgeBaseId, vector, Math.max(40, limit * 8))) {
      vectorScores.put(hit.chunkId(), Math.max(0, hit.score()));
    }
    var candidates = jdbc.query("select c.id,c.document_id,c.chunk_index,c.content,c.source_ref,c.embedding from document_chunks c join source_documents d on c.document_id=d.id where d.knowledge_base_id=? order by c.chunk_index limit 4000",
        (rs, n) -> new Candidate(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("document_id")),
            rs.getInt("chunk_index"), rs.getString("content"), rs.getString("source_ref"), rs.getString("embedding")),
        knowledgeBaseId);
    return candidates.stream().map(candidate -> {
      double semantic = vectorScores.getOrDefault(candidate.id(), embeddingScore(vector, candidate.embedding()));
      double lexical = lexicalScore(query, candidate.content());
      double combined = lexical * 0.72 + semantic * 0.28;
      return new Hit(candidate.id(), candidate.documentId(), candidate.index(), candidate.content(), candidate.sourceRef(), combined);
    }).sorted(Comparator.comparing(Hit::score).reversed()).limit(limit).toList();
  }

  /** Retrieves evidence from one source document only, preventing cross-standard context mixing. */
  public List<Hit> searchDocument(UUID documentId, String query, int topK) {
    access.assertDocument(documentId);
    if (query == null || query.isBlank()) throw new IllegalArgumentException("检索关键词不能为空");
    int limit = Math.max(1, Math.min(topK, 20));
    UUID knowledgeBaseId = jdbc.queryForObject("select knowledge_base_id from source_documents where id=?", UUID.class, documentId);
    float[] vector = embeddings.embed(query);
    Map<UUID, Double> vectorScores = new HashMap<>();
    for (Hit hit : vectors.search(knowledgeBaseId, vector, 240)) {
      if (documentId.equals(hit.documentId())) vectorScores.put(hit.chunkId(), Math.max(0, hit.score()));
    }
    var candidates = jdbc.query("select id,document_id,chunk_index,content,source_ref,embedding from document_chunks where document_id=? order by chunk_index limit 4000",
        (rs, n) -> new Candidate(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("document_id")),
            rs.getInt("chunk_index"), rs.getString("content"), rs.getString("source_ref"), rs.getString("embedding")), documentId);
    return candidates.stream().map(candidate -> {
      double semantic = vectorScores.getOrDefault(candidate.id(), embeddingScore(vector, candidate.embedding()));
      double lexical = lexicalScore(query, candidate.content());
      return new Hit(candidate.id(), candidate.documentId(), candidate.index(), candidate.content(), candidate.sourceRef(), lexical * 0.72 + semantic * 0.28);
    }).sorted(Comparator.comparing(Hit::score).reversed()).limit(limit).toList();
  }

  private double lexicalScore(String query, String content) {
    String normalizedQuery = normalize(query), normalizedContent = normalize(content);
    if (normalizedQuery.isBlank() || normalizedContent.isBlank()) return 0;
    Set<String> queryGrams = grams(normalizedQuery), contentGrams = grams(normalizedContent);
    long gramMatches = queryGrams.stream().filter(contentGrams::contains).count();
    double gramRecall = queryGrams.isEmpty() ? 0 : gramMatches / (double) queryGrams.size();

    List<String> terms = new ArrayList<>();
    for (String raw : query.split("[｜|：:，,。；;\\n]") ) {
      String term = normalize(raw.replaceFirst("^(职业功能|工作内容|技能要求|相关知识|考点)", ""));
      if (term.length() >= 2 && term.length() <= 36) terms.add(term);
    }
    long termMatches = terms.stream().filter(normalizedContent::contains).count();
    double termRecall = terms.isEmpty() ? 0 : termMatches / (double) terms.size();
    double exactBonus = terms.stream().anyMatch(term -> term.length() >= 6 && normalizedContent.contains(term)) ? 0.16 : 0;
    return Math.min(1, gramRecall * 0.55 + termRecall * 0.35 + exactBonus);
  }

  private Set<String> grams(String value) {
    Set<String> result = new LinkedHashSet<>();
    for (int i = 0; i + 1 < value.length(); i++) result.add(value.substring(i, i + 2));
    return result;
  }

  private String normalize(String value) {
    return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{IsHan}a-z0-9]", "");
  }

  private double embeddingScore(float[] query, String encoded) {
    try {
      String value = encoded != null && encoded.startsWith("\"") ? json.readValue(encoded, String.class) : encoded;
      return Math.max(0, embeddings.cosine(query, json.readValue(value, float[].class)));
    } catch (Exception e) { return 0; }
  }

  private record Candidate(UUID id, UUID documentId, int index, String content, String sourceRef, String embedding) { }
  public record Hit(UUID chunkId, UUID documentId, int chunkIndex, String content, String sourceRef, double score) { }
}
