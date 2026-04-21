package com.clmcat.tock.serialize;

public interface Serializer {
    static byte JAVA_VERSION = 0x01;
    static byte JSON_VERSION = 0x02;
    static byte KRYO_VERSION = 0x03;
    static byte PROTOBUF_VERSION = 0x04;
    static byte XML_VERSION = 0x05;


    byte[] serialize(Object obj) throws Exception;
    <T> T deserialize(byte[] data, Class<T> clazz) throws Exception;

    byte version();
}