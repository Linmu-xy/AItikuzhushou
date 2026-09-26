package com.tikuzhushou.document;

import io.minio.*;import io.minio.messages.Item;import java.io.*;import java.nio.file.*;import org.springframework.beans.factory.annotation.Value;import org.springframework.stereotype.Service;

/** Production object store with local development fallback. */
@Service public class ObjectStorageService {
  private final boolean minio; private final Path local; private final String bucket; private final MinioClient client;
  public ObjectStorageService(@Value("${app.storage.provider:local}") String provider,@Value("${app.storage.local-path:./storage}") String path,@Value("${app.storage.minio-endpoint:}") String endpoint,@Value("${app.storage.minio-bucket:tikuzhushou}") String bucket,@Value("${MINIO_ROOT_USER:}") String user,@Value("${MINIO_ROOT_PASSWORD:}") String password){this.minio="minio".equalsIgnoreCase(provider);this.local=Path.of(path);this.bucket=bucket;this.client=minio?MinioClient.builder().endpoint(endpoint).credentials(user,password).build():null;}
  public String put(String key,InputStream stream,long size,String type)throws Exception{if(!minio){Files.createDirectories(local);Path target=local.resolve(key);Files.copy(stream,target,StandardCopyOption.REPLACE_EXISTING);return target.toString();}if(!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build()))client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());client.putObject(PutObjectArgs.builder().bucket(bucket).object(key).stream(stream,size,-1).contentType(type).build());return "minio://"+bucket+"/"+key;}
  public Path materialize(String stored,String suffix)throws Exception{if(!stored.startsWith("minio://"))return Path.of(stored);String object=stored.substring(("minio://"+bucket+"/").length());Path temp=Files.createTempFile("tiku-",suffix);try(InputStream in=client.getObject(GetObjectArgs.builder().bucket(bucket).object(object).build())){Files.copy(in,temp,StandardCopyOption.REPLACE_EXISTING);}return temp;}
  /** Only deletes a temporary MinIO materialization; local source files are never removed by workers. */
  public void cleanupMaterialized(Path path)throws IOException{if(minio&&path!=null)Files.deleteIfExists(path);}
}
