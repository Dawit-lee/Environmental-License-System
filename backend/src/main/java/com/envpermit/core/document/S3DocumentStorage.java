package com.envpermit.core.document;

import java.io.InputStream;
import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;

/** S3-compatible storage. Endpoint set → MinIO (path-style); blank → AWS S3. */
@Component
class S3DocumentStorage implements DocumentStorage {

    private final S3Client s3;
    private final String bucket;

    S3DocumentStorage(@Value("${app.storage.endpoint}") String endpoint,
                      @Value("${app.storage.region}") String region,
                      @Value("${app.storage.bucket}") String bucket,
                      @Value("${app.storage.access-key}") String accessKey,
                      @Value("${app.storage.secret-key}") String secretKey) {
        S3ClientBuilder b = S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)));
        if (!endpoint.isBlank()) {
            b.endpointOverride(URI.create(endpoint)).forcePathStyle(true);
        }
        this.s3 = b.build();
        this.bucket = bucket;
        createBucketIfMissing();
    }

    // local MinIO starts empty; on AWS the bucket already exists and this is a no-op
    private void createBucketIfMissing() {
        try {
            s3.headBucket(r -> r.bucket(bucket));
        } catch (NoSuchBucketException e) {
            s3.createBucket(r -> r.bucket(bucket));
        }
    }

    @Override
    public void put(String key, InputStream content, long size, String contentType) {
        s3.putObject(r -> r.bucket(bucket).key(key).contentType(contentType),
                RequestBody.fromInputStream(content, size));
    }

    @Override
    public InputStream open(String key) {
        return s3.getObject(r -> r.bucket(bucket).key(key));
    }

    @Override
    public void delete(String key) {
        s3.deleteObject(r -> r.bucket(bucket).key(key));
    }
}
