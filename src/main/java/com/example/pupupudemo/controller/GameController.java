package com.example.pupupudemo.controller;

import com.example.pupupudemo.model.Agent;
import com.example.pupupudemo.model.WorldResource;
import com.example.pupupudemo.repository.AgentRepository;
import com.example.pupupudemo.repository.ResourceRepository;
import com.example.pupupudemo.service.GameLoopService; // 👈 导入这个
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class GameController {

    @Autowired
    private AgentRepository agentRepository;
    @Autowired
    private ResourceRepository resourceRepository;
    @Autowired
    private GameLoopService gameLoopService; // 👈 注入 GameLoopService

    @GetMapping("/gamestate")
    public GameState getGameState() {
        // 🔥 获取最新的日志，一起打包发给前端
        List<String> logs = gameLoopService.getLatestLogs();
        return new GameState(agentRepository.findAll(), resourceRepository.findAll(), logs);
    }

    @Data
    public static class GameState {
        private List<Agent> agents;
        private List<WorldResource> resources;
        private List<String> logs; // 👈 新增日志字段

        public GameState(List<Agent> agents, List<WorldResource> resources, List<String> logs) {
            this.agents = agents;
            this.resources = resources;
            this.logs = logs;
        }
    }
}