package com.example.pupupudemo.service;

import com.example.pupupudemo.model.Agent;
import com.example.pupupudemo.model.WorldResource;
import com.example.pupupudemo.repository.AgentRepository;
import com.example.pupupudemo.repository.ResourceRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;

@Service
public class GameLoopService {

    @Autowired
    private AgentRepository agentRepository;
    @Autowired
    private ResourceRepository resourceRepository;
    @Autowired
    private AgentAiService aiService;
    @Autowired
    private LiveDataService liveDataService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Random random = new Random();

    // 🔥 日志队列：保留最近 50 条，确保前端能看到完整的滚动历史
    private final LinkedList<String> logHistory = new LinkedList<>();

    // 提供给 Controller 获取日志
    public List<String> getLatestLogs() {
        synchronized (logHistory) {
            return new ArrayList<>(logHistory);
        }
    }

    // 通用记录日志方法
    private void log(String message) {
        System.out.println(message); // 控制台打印
        synchronized (logHistory) {
            logHistory.add(message); // 存入队列
            if (logHistory.size() > 50) { // 如果超过 50 条，删除最早的
                logHistory.removeFirst();
            }
        }
    }

    @Scheduled(fixedRate = 5000)
    public void runGameTurn() {
        log("----- 新回合开始 -----");

        // 1. 获取环境消耗
        int hpCost = liveDataService.getHpCostPerTurn();
        log(">>> 当前环境消耗: -" + hpCost + " HP (受 BTC 价格影响)");

        List<Agent> agents = agentRepository.findAll();
        List<WorldResource> allResources = resourceRepository.findAll();

        for (Agent agent : agents) {
            // 2. 生存与进食
            int wheatCount = agent.getInventory().getOrDefault("Wheat", 0);
            if (wheatCount > 0) {
                agent.addResource("Wheat", -1);
                agent.setLifespan(Math.min(agent.getLifespan() + 5, 100));
                log("🍞 " + agent.getName() + " 进食回血 (HP=" + agent.getLifespan() + ")");
            } else {
                agent.setLifespan(agent.getLifespan() - hpCost);
                // 🔥 修改：取消了频率限制，现在每回合只要扣血就会提示
                log("⚠️ " + agent.getName() + " 正在挨饿 (HP=" + agent.getLifespan() + ")");
            }

            if (agent.getLifespan() <= 0) {
                log("💀 " + agent.getName() + " 遗憾离世。");
                agentRepository.delete(agent);
                continue;
            }

            // 3. 观察环境 (3x3)
            List<String> visibleItems = new ArrayList<>();
            WorldResource targetToHarvest = null;
            for (WorldResource res : allResources) {
                int dx = Math.abs(res.getX() - agent.getX());
                int dy = Math.abs(res.getY() - agent.getY());
                if (dx <= 1 && dy <= 1) {
                    if (dx == 0 && dy == 0) {
                        visibleItems.add("STANDING_ON " + res.getType());
                        targetToHarvest = res;
                    } else {
                        visibleItems.add(res.getType() + " at (" + res.getX() + "," + res.getY() + ")");
                    }
                }
            }
            String envDescription = visibleItems.isEmpty() ? "Empty space" : "See: " + String.join(", ", visibleItems);

            // 4. AI Prompt
            String agentState = String.format("""
                {
                    "id": "%s", "hp": %d, "inventory": %s,
                    "loc": {"x": %d, "y": %d},
                    "grid": "20x20. (0,0) Top-Left. y+ is DOWN.",
                    "vision": "%s",
                    "instruction": "Explore randomly if empty. Do NOT stay in corners."
                }
                """, agent.getName(), agent.getLifespan(), agent.getInventory(), agent.getX(), agent.getY(), envDescription);

            try {
                // 5. AI 决策
                String aiResponseJson = aiService.getAgentDecision(agentState);
                JsonNode decision = objectMapper.readTree(aiResponseJson);
                String action = decision.has("action") ? decision.get("action").asText() : "WAIT";

                // 6. 执行动作 (传入所有 agents 用于碰撞检测)
                executeAction(agent, action, decision, targetToHarvest, agents);

            } catch (Exception e) {
                System.err.println("AI Error: " + e.getMessage());
            }
            agentRepository.save(agent);
        }

        // 7. 生态维护
        checkAndRespawnAgents();
        checkAndRespawnResources();
    }

    private void executeAction(Agent agent, String action, JsonNode decision, WorldResource resourceUnderFeet, List<Agent> allAgents) {
        switch (action.toUpperCase()) {
            case "MOVE" -> {
                String dir = decision.has("direction") ? decision.get("direction").asText() : getRandomDirection();

                int newX = agent.getX();
                int newY = agent.getY();

                if ("UP".equalsIgnoreCase(dir)) newY--;
                if ("DOWN".equalsIgnoreCase(dir)) newY++;
                if ("LEFT".equalsIgnoreCase(dir)) newX--;
                if ("RIGHT".equalsIgnoreCase(dir)) newX++;

                if (isValidMove(newX, newY) && !isOccupied(newX, newY, allAgents)) {
                    agent.setX(newX);
                    agent.setY(newY);
                    // 🔥 修改：取消注释，开启移动日志
                    log("👉 " + agent.getName() + " -> " + dir + " (" + newX + "," + newY + ")");
                } else {
                    // 撞墙处理：强制随机走一步
                    forceRandomMove(agent, allAgents);
                }
            }
            case "HARVEST" -> {
                if (resourceUnderFeet != null) {
                    agent.addResource(resourceUnderFeet.getType(), 1);
                    resourceRepository.delete(resourceUnderFeet);
                    log("🎉 " + agent.getName() + " 采集了 " + resourceUnderFeet.getType());
                }
            }
            case "CRAFT" -> {
                if (agent.getInventory().getOrDefault("Stone", 0) >= 2) {
                    agent.addResource("Stone", -2);
                    agent.addResource("Axe", 1);
                    log("🔨 " + agent.getName() + " 合成 Axe!");
                }
            }
        }
    }

    // --- 辅助方法 ---

    private boolean isValidMove(int x, int y) {
        return x >= 0 && x < 20 && y >= 0 && y < 20;
    }

    private boolean isOccupied(int x, int y, List<Agent> agents) {
        for (Agent a : agents) {
            if (a.isAlive() && a.getX() == x && a.getY() == y) {
                return true;
            }
        }
        return false;
    }

    private void forceRandomMove(Agent agent, List<Agent> allAgents) {
        int[] dx = {0, 0, -1, 1};
        int[] dy = {-1, 1, 0, 0};
        for (int i = 0; i < 4; i++) {
            int r = random.nextInt(4);
            int tryX = agent.getX() + dx[r];
            int tryY = agent.getY() + dy[r];
            if (isValidMove(tryX, tryY) && !isOccupied(tryX, tryY, allAgents)) {
                agent.setX(tryX);
                agent.setY(tryY);
                return;
            }
        }
    }

    private String getRandomDirection() {
        String[] dirs = {"UP", "DOWN", "LEFT", "RIGHT"};
        return dirs[random.nextInt(dirs.length)];
    }

    private void checkAndRespawnAgents() {
        if (agentRepository.count() < 5) {
            for (int i = 0; i < 5 - agentRepository.count(); i++) {
                Agent newAgent = new Agent();
                newAgent.setName("New_" + (System.currentTimeMillis() % 1000));
                newAgent.setX(random.nextInt(20));
                newAgent.setY(random.nextInt(20));
                newAgent.setLifespan(50);
                newAgent.setAlive(true);
                newAgent.addResource("Wheat", 0);
                newAgent.addResource("Stone", 0);
                agentRepository.save(newAgent);
                log("👶 新人加入: " + newAgent.getName());
            }
        }
    }

    private void checkAndRespawnResources() {
        if (resourceRepository.count() < 20) {
            log("🌱 资源再生...");
            for (int i = 0; i < 5; i++) {
                WorldResource res = new WorldResource();
                res.setType(random.nextBoolean() ? "Wheat" : "Stone");
                res.setX(random.nextInt(20));
                res.setY(random.nextInt(20));
                resourceRepository.save(res);
            }
        }
    }
}