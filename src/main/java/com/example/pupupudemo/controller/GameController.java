package com.example.pupupudemo.controller;

import com.example.pupupudemo.model.Agent;
import com.example.pupupudemo.model.WorldExit; // 👈 确保导入这个
import com.example.pupupudemo.model.WorldResource;
import com.example.pupupudemo.repository.AgentRepository;
import com.example.pupupudemo.repository.ExitRepository; // 👈 确保导入这个
import com.example.pupupudemo.repository.ResourceRepository;
import com.example.pupupudemo.service.GameLoopService;
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

    @Autowired private AgentRepository agentRepository;
    @Autowired private ResourceRepository resourceRepository;
    @Autowired private ExitRepository exitRepository; // 👈 必须注入这个
    @Autowired private GameLoopService gameLoopService;

    @GetMapping("/gamestate")
    public GameState getGameState() {
        List<String> logs = gameLoopService.getLatestLogs();
        return new GameState(
                agentRepository.findAll(),
                resourceRepository.findAll(),
                exitRepository.findAll(), // 👈 必须把查到的出口放进去
                logs
        );
    }

    @Data
    public static class GameState {
        private List<Agent> agents;
        private List<WorldResource> resources;
        private List<WorldExit> exits; // 👈 必须有这个字段，前端才能读到
        private List<String> logs;

        public GameState(List<Agent> agents, List<WorldResource> resources, List<WorldExit> exits, List<String> logs) {
            this.agents = agents;
            this.resources = resources;
            this.exits = exits;
            this.logs = logs;
        }
    }
}