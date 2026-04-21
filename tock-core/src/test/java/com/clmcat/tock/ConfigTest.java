package com.clmcat.tock;

import com.clmcat.tock.registry.TockRegister;
import com.clmcat.tock.registry.memory.MemoryTockMaster;
import com.clmcat.tock.money.MemoryManager;
import com.clmcat.tock.registry.memory.MemoryTockRegister;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class ConfigTest {

    // 测试Config配置
    @Test
    void shouldNewConfig() {


        TockRegister register = new MemoryTockRegister("test-timer", MemoryManager.create());

        Assertions.assertThrows(Exception.class, ()-> Config.builder()
                .register(register)
                .serializer(null)
                .scheduleStore(null)
                .jobStore(null)
                .workerQueue(null)
                .build());

    }

}
