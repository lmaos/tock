package com.clmcat.tock.serialize;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class SerializerFactoryTest {

    @Test
    void shouldReturnSingletonDefaultSerializer() {
        Serializer first = SerializerFactory.getDefault();
        Serializer second = SerializerFactory.getDefault();

        Assertions.assertNotNull(first);
        Assertions.assertSame(first, second);
    }
}
