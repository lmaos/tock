package com.clmcat.tock.serialize;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.serializer.SerializerFeature;

public class FastjsonSerializer implements Serializer {
    @Override
    public byte[] serialize(Object obj) {
        return JSON.toJSONBytes(obj, SerializerFeature.WriteClassName, SerializerFeature.DisableCircularReferenceDetect);
    }

    @Override
    public <T> T deserialize(byte[] data, Class<T> clazz) {
        return JSON.parseObject(data, clazz);
    }

    @Override
    public byte version() {
        return JSON_VERSION;
    }

}