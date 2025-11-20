package com.example.pupupudemo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import com.example.pupupudemo.service.WorldService; // 导入你刚才写的 Service

@SpringBootTest
class LivingGridApplicationTests {

    @Autowired
    private WorldService worldService;

    @Test
    void testWorldInitialization() {
        // 运行初始化逻辑
        worldService.initializeWorld();

        // 如果控制台打印出 "5 位勇士已降临"，说明写入 MongoDB 成功！
    }
}