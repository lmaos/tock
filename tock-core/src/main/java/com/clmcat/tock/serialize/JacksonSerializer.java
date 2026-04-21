package com.clmcat.tock.serialize;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;

import java.io.IOException;

public class JacksonSerializer implements Serializer {
    private final ObjectMapper mapper = new ObjectMapper();

    public JacksonSerializer() {
        // 禁用循环引用检测（写自身引用为 null）
        // mapper.enable(SerializationFeature.WRITE_SELF_REFERENCES_AS_NULL);
        // 启用默认类型（保留类信息，类似 WriteClassName）
        mapper.activateDefaultTyping(LaissezFaireSubTypeValidator.instance, ObjectMapper.DefaultTyping.NON_FINAL);
        // 忽略未知属性
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        // 日期使用时间戳
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Override
    public byte[] serialize(Object obj) throws IOException {
        return mapper.writeValueAsBytes(obj);
    }

    @Override
    public <T> T deserialize(byte[] data, Class<T> clazz) throws IOException {
        return mapper.readValue(data, clazz);
    }

    @Override
    public byte version() {
        return JSON_VERSION;
    }
}