package com.envpermit.core.document;

import java.io.InputStream;

/** Object storage for document bytes (S3/MinIO in the app, in-memory fake in tests). */
public interface DocumentStorage {

    void put(String key, InputStream content, long size, String contentType);

    /** Caller closes the stream. */
    InputStream open(String key);

    void delete(String key);
}
