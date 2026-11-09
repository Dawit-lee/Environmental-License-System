package com.envpermit.support;

import com.envpermit.core.document.DocumentStorage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;

/** Fake object storage for tests. */
public class InMemoryDocumentStorage implements DocumentStorage {

    public final Map<String, byte[]> objects = new ConcurrentHashMap<>();

    @Override
    public void put(String key, InputStream content, long size, String contentType) {
        try {
            objects.put(key, content.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public InputStream open(String key) {
        byte[] bytes = objects.get(key);
        if (bytes == null) {
            throw new NoSuchElementException(key);
        }
        return new ByteArrayInputStream(bytes);
    }

    @Override
    public void delete(String key) {
        objects.remove(key);
    }
}
