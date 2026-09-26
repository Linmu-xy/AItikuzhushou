package com.tikuzhushou.retrieval;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** PostgreSQL production path; development H2 keeps using JSON embeddings. */
@Service public class PostgresVectorStore {
  private final JdbcTemplate jdbc; private volatile Boolean postgres;
  public PostgresVectorStore(JdbcTemplate jdbc){this.jdbc=jdbc;}
  public boolean enabled(){if(postgres!=null)return postgres;try(var c=jdbc.getDataSource().getConnection()){postgres=c.getMetaData().getDatabaseProductName().toLowerCase().contains("postgresql");if(postgres)install();return postgres;}catch(Exception e){postgres=false;return false;}}
  private void install(){jdbc.execute("create extension if not exists vector");jdbc.execute("alter table document_chunks add column if not exists embedding_vector vector(128)");jdbc.execute("create index if not exists idx_document_chunks_vector_hnsw on document_chunks using hnsw (embedding_vector vector_cosine_ops)");}
  public void save(UUID chunkId,float[] value){if(enabled())jdbc.update("update document_chunks set embedding_vector=cast(? as vector) where id=?",literal(value),chunkId);}
  public List<RetrievalService.Hit> search(UUID baseId,float[] query,int limit){if(!enabled())return List.of();return jdbc.query("select c.id,c.document_id,c.chunk_index,c.content,c.source_ref,1-(c.embedding_vector <=> cast(? as vector)) score from document_chunks c join source_documents d on c.document_id=d.id where d.knowledge_base_id=? and c.embedding_vector is not null order by c.embedding_vector <=> cast(? as vector) limit ?",(rs,n)->new RetrievalService.Hit(UUID.fromString(rs.getString("id")),UUID.fromString(rs.getString("document_id")),rs.getInt("chunk_index"),rs.getString("content"),rs.getString("source_ref"),rs.getDouble("score")),literal(query),baseId,literal(query),limit);}
  private String literal(float[] values){StringBuilder b=new StringBuilder("[");for(int i=0;i<values.length;i++){if(i>0)b.append(',');b.append(values[i]);}return b.append(']').toString();}
}
