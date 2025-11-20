package com.example.pupupudemo.service;

import com.example.pupupudemo.model.Agent;
import com.example.pupupudemo.model.WorldResource;
import com.example.pupupudemo.repository.AgentRepository;
import com.example.pupupudemo.repository.ResourceRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Random;

@Service
public class WorldService {

    @Autowired
    private AgentRepository agentRepository;

    @Autowired
    private ResourceRepository resourceRepository;

    private final Random random = new Random();
    private final int GRID_SIZE = 20;

    public void initializeWorld() {
        agentRepository.deleteAll();
        resourceRepository.deleteAll();
        System.out.println(">>> 初始化世界...");

        // 生成 Agent
        for (int i = 1; i <= 5; i++) {
            Agent agent = Agent.builder() // 现在这里应该能用了
                    .name("Agent_" + i)
                    .x(random.nextInt(GRID_SIZE))
                    .y(random.nextInt(GRID_SIZE))
                    .lifespan(50)
                    .isAlive(true)
                    .build();

            agent.addResource("Wheat", 0);
            agent.addResource("Stone", 0);
            agentRepository.save(agent);
        }

        // 生成资源
        for (int i = 0; i < 10; i++) {
            String type = random.nextBoolean() ? "Wheat" : "Stone";
            WorldResource res = WorldResource.builder() // 这里也能用了
                    .type(type)
                    .x(random.nextInt(GRID_SIZE))
                    .y(random.nextInt(GRID_SIZE))
                    .build();
            resourceRepository.save(res);
        }
        System.out.println(">>> 世界构建完成。");
    }
}