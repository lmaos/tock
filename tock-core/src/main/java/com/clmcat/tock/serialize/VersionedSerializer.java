package com.clmcat.tock.serialize;

import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;

@Slf4j
public class VersionedSerializer implements Serializer {
    private final Map<Byte, Serializer> serializers = new HashMap<>();
    private final Serializer defaultSerializer;

    public VersionedSerializer(Serializer defaultSerializer) {
        this.defaultSerializer = defaultSerializer;
        register(this.defaultSerializer.version(), defaultSerializer);

        if (hasClass("com.fasterxml.jackson.databind.ObjectMapper")) {
            register(JSON_VERSION, new JacksonSerializer());
        }
        if (hasClass("com.alibaba.fastjson.JSON")) {
            register(JSON_VERSION, new FastjsonSerializer());
        }
        if (hasClass("com.esotericsoftware.kryo.Kryo")) {
            register(KRYO_VERSION, new KryoSerializer());
        }

        register(JAVA_VERSION, new JavaSerializer());
    }

    private void register(byte version, Serializer serializer) {
        serializers.putIfAbsent(version, serializer);
        log.debug("Registered serializer version {}: {}", version, serializer.getClass().getSimpleName());
    }

    @Override
    public byte[] serialize(Object obj) throws Exception {
        byte[] data = defaultSerializer.serialize(obj);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        baos.write(getVersion(defaultSerializer));
        baos.write(data);
        return baos.toByteArray();
    }

    @Override
    public <T> T deserialize(byte[] data, Class<T> clazz) throws Exception {
        if (data == null || data.length == 0) return null;
        byte version = data[0];
        byte[] payload = new byte[data.length - 1];
        System.arraycopy(data, 1, payload, 0, payload.length);
        Serializer serializer = serializers.get(version);
        if (serializer == null) {
            throw new IllegalArgumentException("Unsupported serializer version: " + version);
        }
        return serializer.deserialize(payload, clazz);
    }

    @Override
    public byte version() {
        return defaultSerializer.version();
    }

    private byte getVersion(Serializer serializer) {
        for (Map.Entry<Byte, Serializer> entry : serializers.entrySet()) {
            if (entry.getValue().getClass() == serializer.getClass()) {
                return entry.getKey();
            }
        }
        // 如果未找到，回退到 Java 序列化版本（0x01）
        return 0x01;
    }

    private boolean hasClass(String className) {
        try {
            Class.forName(className, false, Thread.currentThread().getContextClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}