package com.example.pupupudemo.service;

import com.example.pupupudemo.model.Agent;
import com.example.pupupudemo.model.WorldExit;
import com.example.pupupudemo.model.WorldResource;
import com.example.pupupudemo.repository.AgentRepository;
import com.example.pupupudemo.repository.ExitRepository;
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

    @Autowired private AgentRepository agentRepository;
    @Autowired private ResourceRepository resourceRepository;
    @Autowired private ExitRepository exitRepository;
    @Autowired private AgentAiService aiService;
    @Autowired private LiveDataService liveDataService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Random random = new Random();

    // 日志队列
    private final LinkedList<String> logHistory = new LinkedList<>();

    public List<String> getLatestLogs() {
        synchronized (logHistory) { return new ArrayList<>(logHistory); }
    }

    private void log(String message) {
        System.out.println(message);
        synchronized (logHistory) {
            logHistory.add(message);
            if (logHistory.size() > 50) logHistory.removeFirst();
        }
    }

    @Scheduled(fixedRate = 5000)
    public void runGameTurn() {
        log("----- 新回合开始 -----");
        int hpCost = liveDataService.getHpCostPerTurn();

        // 0. 维护世界：确保有出口
        checkAndRespawnExits();

        List<Agent> agents = agentRepository.findAll();
        List<WorldResource> allResources = resourceRepository.findAll();
        List<WorldExit> allExits = exitRepository.findAll();

        for (Agent agent : agents) {
            boolean hasAxe = agent.getInventory().getOrDefault("Axe", 0) > 0;

            // =================================================
            // 1. 统一生存逻辑
            // =================================================
            int wheatCount = agent.getInventory().getOrDefault("Wheat", 0);
            if (wheatCount > 0) {
                agent.addResource("Wheat", -1);
                agent.setLifespan(Math.min(agent.getLifespan() + 5, 100));
                log("🍞 " + agent.getName() + " 进食回血 (HP=" + agent.getLifespan() + ")");
            } else {
                agent.setLifespan(agent.getLifespan() - hpCost);
                log("⚠️ " + agent.getName() + " 正在挨饿 (HP=" + agent.getLifespan() + ")");
            }

            if (agent.getLifespan() <= 0) {
                log("💀 " + agent.getName() + (hasAxe ? " 带着斧头遗憾离世。" : " 饿死了。"));
                agentRepository.delete(agent);
                continue;
            }

            // =================================================
            // 2. 视野逻辑 (Resources + Exits)
            // =================================================
            List<String> visibleItems = new ArrayList<>();
            WorldResource resourceUnderFeet = null;

            // A. 找资源 (3x3)
            for (WorldResource res : allResources) {
                int dx = Math.abs(res.getX() - agent.getX());
                int dy = Math.abs(res.getY() - agent.getY());
                if (dx == 0 && dy == 0) {
                    visibleItems.add("STANDING_ON RESOURCE " + res.getType());
                    resourceUnderFeet = res;
                } else if (dx <= 1 && dy <= 1) {
                    visibleItems.add(res.getType() + " at (" + res.getX() + "," + res.getY() + ")");
                }
            }

            // B. 找出口 (持有斧头时视野变为 10x10)
            int visionRange = hasAxe ? 5 : 1;
            for (WorldExit exit : allExits) {
                int dx = Math.abs(exit.getX() - agent.getX());
                int dy = Math.abs(exit.getY() - agent.getY());

                if (dx == 0 && dy == 0) {
                    visibleItems.add("STANDING_ON EXIT");
                } else if (dx <= visionRange && dy <= visionRange) {
                    // 只有拿着斧头或者紧邻出口时才能看见
                    if (hasAxe || (dx <= 1 && dy <= 1)) {
                        visibleItems.add("EXIT at (" + exit.getX() + "," + exit.getY() + ")");
                    }
                }
            }

            String envDescription = visibleItems.isEmpty() ? "Nothing nearby" : "You see: " + String.join(", ", visibleItems);

            // =================================================
            // 3. 构建 Prompt
            // =================================================
            String instructions;
            if (hasAxe) {
                // 拿到斧头后，指令变为纯移动，无需特意 ESCAPE，只要走到就行
                instructions = "URGENT: You have an Axe! IGNORE resources. MOVE to the 'EXIT' coordinates immediately! Just stand on it!";
            } else {
                instructions = "Goal: Survive. Harvest Wheat. Craft Axe (need 2 Stone). If standing on resource, HARVEST.";
            }

            String agentState = String.format("""
                {
                    "id": "%s", "hp": %d, "inventory": %s,
                    "loc": {"x": %d, "y": %d},
                    "vision": "%s",
                    "instruction": "%s"
                }
                """, agent.getName(), agent.getLifespan(), agent.getInventory(), agent.getX(), agent.getY(), envDescription, instructions);

            try {
                // 4. AI 决策
                String aiResponseJson = aiService.getAgentDecision(agentState);
                JsonNode decision = objectMapper.readTree(aiResponseJson);
                String action = decision.has("action") ? decision.get("action").asText() : "WAIT";

                // 5. 执行动作
                executeAction(agent, action, decision, resourceUnderFeet, agents, hasAxe);

                // =================================================
                // 🔥 6. 自动逃生检测 (Auto-Trigger)
                // =================================================
                // 动作执行完后（比如刚 MOVE 完），立即检查是不是站在出口上
                // 如果是，直接判定胜利，不需要 AI 发送 ESCAPE 指令
                boolean escaped = false;
                for (WorldExit exit : allExits) {
                    if (exit.getX() == agent.getX() && exit.getY() == agent.getY() && hasAxe) {
                        log("🚀🚀🚀 " + agent.getName() + " 成功带着斧头逃离了矩阵！(HP=" + agent.getLifespan() + ")");

                        // 删除数据
                        agentRepository.delete(agent);
                        exitRepository.delete(exit);

                        // 补充生态
                        spawnNewAgent();
                        spawnNewExit();

                        escaped = true;
                        break; // 跳出出口循环
                    }
                }

                // 如果逃走了，就不保存 agent 了，直接处理下一个 agent
                if (escaped) continue;

            } catch (Exception e) {
                System.err.println("AI Error: " + e.getMessage());
            }

            // 如果还活着且没逃走，保存状态
            if (agentRepository.existsById(agent.getId())) {
                agentRepository.save(agent);
            }
        }

        // 补充 Agent 和 Resource
        checkAndRespawnAgents();
        checkAndRespawnResources();
    }

    // --- 动作执行 ---
    private void executeAction(Agent agent, String action, JsonNode decision,
                               WorldResource resourceUnderFeet, List<Agent> allAgents, boolean hasAxe) {
        switch (action.toUpperCase()) {
            case "MOVE" -> {
                String dir = decision.has("direction") ? decision.get("direction").asText() : "UP";
                int newX = agent.getX(), newY = agent.getY();
                if ("UP".equalsIgnoreCase(dir)) newY--;
                if ("DOWN".equalsIgnoreCase(dir)) newY++;
                if ("LEFT".equalsIgnoreCase(dir)) newX--;
                if ("RIGHT".equalsIgnoreCase(dir)) newX++;

                if (isValidMove(newX, newY) && !isOccupied(newX, newY, allAgents)) {
                    agent.setX(newX); agent.setY(newY);
                    if (hasAxe) log("🏃 " + agent.getName() + " (持斧) -> " + dir + " (" + newX + "," + newY + ")");
                } else {
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
                    log("🔨 " + agent.getName() + " 打造出了传说之斧! 快去找出口！");
                }
            }
            // 移除了 ESCAPE case，完全依靠自动检测
        }
    }

    // --- 辅助方法 ---
    private void checkAndRespawnExits() {
        if (exitRepository.count() < 5) {
            for (int i = 0; i < 5 - exitRepository.count(); i++) spawnNewExit();
        }
    }
    private void spawnNewExit() {
        WorldExit exit = new WorldExit(random.nextInt(20), random.nextInt(20));
        exitRepository.save(exit);
        log("🚪 新出口出现在 (" + exit.getX() + "," + exit.getY() + ")");
    }
    private void spawnNewAgent() {
        Agent newAgent = new Agent();
        newAgent.setName("Newborn_" + (System.currentTimeMillis() % 1000));
        newAgent.setX(random.nextInt(20));
        newAgent.setY(random.nextInt(20));
        newAgent.setLifespan(50);
        newAgent.setAlive(true);
        newAgent.addResource("Wheat", 0);
        newAgent.addResource("Stone", 0);
        agentRepository.save(newAgent);
        log("👶 新挑战者加入: " + newAgent.getName());
    }
    private void checkAndRespawnAgents() {
        if (agentRepository.count() < 5) spawnNewAgent();
    }
    private void checkAndRespawnResources() {
        if (resourceRepository.count() < 20) {
            for (int i = 0; i < 5; i++) {
                WorldResource res = new WorldResource();
                res.setType(random.nextBoolean() ? "Wheat" : "Stone");
                res.setX(random.nextInt(20));
                res.setY(random.nextInt(20));
                resourceRepository.save(res);
            }
        }
    }
    private boolean isValidMove(int x, int y) { return x >= 0 && x < 20 && y >= 0 && y < 20; }
    private boolean isOccupied(int x, int y, List<Agent> agents) {
        for (Agent a : agents) if (a.isAlive() && a.getX() == x && a.getY() == y) return true;
        return false;
    }
    private void forceRandomMove(Agent agent, List<Agent> allAgents) {
        int[] dx = {0, 0, -1, 1}; int[] dy = {-1, 1, 0, 0};
        for (int i = 0; i < 4; i++) {
            int r = random.nextInt(4);
            int tryX = agent.getX() + dx[r], tryY = agent.getY() + dy[r];
            if (isValidMove(tryX, tryY) && !isOccupied(tryX, tryY, allAgents)) {
                agent.setX(tryX); agent.setY(tryY); return;
            }
        }
    }
}