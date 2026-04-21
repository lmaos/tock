package com.clmcat.tock.serialize;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SerializerFactory {
    private static volatile Serializer instance;

    /**
     * 默认序列工具根据用户依赖的第三方库自动选择，优先级为：Jackson > Fastjson > Kryo > JavaSerializer。
     * @return 返回默认序列化工具。
     */
    public static Serializer getDefault() {
        if (instance == null) {
            synchronized (SerializerFactory.class) {
                if (instance == null) {
                    instance = createPreferred();
                }
            }
        }
        return instance;
    }

    private static Serializer createPreferred() {
        if (hasClass("com.fasterxml.jackson.databind.ObjectMapper")) {
            try {
                return new JacksonSerializer();
            } catch (Throwable e) {
                log.warn("Jackson found but instantiation failed, fallback", e);
            }
        }
        if (hasClass("com.alibaba.fastjson.JSON")) {
            try {
                return new FastjsonSerializer();
            } catch (Throwable e) {
                log.warn("Fastjson found but instantiation failed, fallback", e);
            }
        }
        if (hasClass("com.esotericsoftware.kryo.Kryo")) {
            try {
                return new KryoSerializer();
            } catch (Throwable e) {
                log.warn("Kryo found but instantiation failed, fallback", e);
            }
        }
        log.info("No third-party serializer available, using JavaSerializer");
        log.info("序列化依赖未找到，使用自动默认Java序列化工具，如需使用其他请导入依赖，例如: jackson, fastjson, Kryo等");
        return new JavaSerializer();
    }

    private static boolean hasClass(String className) {
        try {
            Class.forName(className, false, Thread.currentThread().getContextClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}