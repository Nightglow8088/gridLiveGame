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
import java.util.Collections;
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

    // 修改 1: 频率改为 2000ms (2秒)，配合前端动画
    @Scheduled(fixedRate = 2000)
    public void runGameTurn() {
        log("----- 新回合开始 -----");
        int hpCost = liveDataService.getHpCostPerTurn();

        // 0. 维护世界：确保有出口
        checkAndRespawnExits();

        List<Agent> agents = agentRepository.findAll();
        List<WorldResource> allResources = resourceRepository.findAll();
        List<WorldExit> allExits = exitRepository.findAll();

        // 用于批量操作的列表 (优化 IO 性能)
        List<Agent> agentsToSave = new ArrayList<>();
        List<Agent> agentsToDelete = new ArrayList<>();

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
            }

            if (agent.getLifespan() <= 0) {
                log("💀 " + agent.getName() + (hasAxe ? " 带着斧头遗憾离世。" : " 饿死了。"));
                agentsToDelete.add(agent);
                continue;
            }

            // =================================================
            // 2. 视野逻辑 (修复: 找回丢失的 resourceUnderFeet 定义)
            // =================================================
            List<String> visibleItems = new ArrayList<>();
            WorldResource resourceUnderFeet = null; // <--- 这里定义了

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
                    if (hasAxe || (dx <= 1 && dy <= 1)) {
                        visibleItems.add("EXIT at (" + exit.getX() + "," + exit.getY() + ")");
                    }
                }
            }

            String envDescription = visibleItems.isEmpty() ? "Nothing nearby" : "You see: " + String.join(", ", visibleItems);

            // =================================================
            // 3. 构建 Prompt (修复: 找回丢失的 agentState 定义)
            // =================================================
            String instructions;
            if (hasAxe) {
                instructions = "URGENT: You have an Axe! MOVE to the 'EXIT' coordinates immediately! Do NOT stay at edges.";
            } else {
                instructions = "Goal: Survive. Harvest Wheat. Craft Axe (need 2 Stone). EXPLORE THE CENTER OF MAP, do not hug walls.";
            }

            // <--- 这里定义了 agentState
            String agentState = String.format("""
                {
                    "id": "%s", "hp": %d, "inventory": %s,
                    "loc": {"x": %d, "y": %d},
                    "grid": "20x20. (0,0) Top-Left. y+ is DOWN.",
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
                // 6. 自动逃生检测
                // =================================================
                boolean escaped = false;
                for (WorldExit exit : allExits) {
                    // 检查坐标重合 + 有斧头
                    if (exit.getX() == agent.getX() && exit.getY() == agent.getY() && hasAxe) {
                        log("🚀🚀🚀 " + agent.getName() + " 成功带着斧头逃离了矩阵！(HP=" + agent.getLifespan() + ")");

                        // 标记删除
                        agentsToDelete.add(agent);
                        exitRepository.delete(exit);

                        // 内存操作：从当前回合的 exits 列表中移除
                        allExits.remove(exit);

                        escaped = true;
                        break;
                    }
                }

                if (escaped) continue;

                // 没死也没逃走，加入待保存列表
                agentsToSave.add(agent);

            } catch (Exception e) {
                System.err.println("AI Error: " + e.getMessage());
                // 即使出错，状态可能也变了，也需要保存
                agentsToSave.add(agent);
            }
        }

        // =================================================
        // 修改 2: 批量数据库操作 (减少 IO 消耗)
        // =================================================
        if (!agentsToDelete.isEmpty()) {
            agentRepository.deleteAll(agentsToDelete);
        }
        if (!agentsToSave.isEmpty()) {
            agentRepository.saveAll(agentsToSave);
        }

        // 7. 生态维护
        checkAndRespawnAgents();
        checkAndRespawnResources();
        checkAndRespawnExits();

        System.out.println("----- 回合结束 -----\n");
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
                    forceSmartRandomMove(agent, allAgents);
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
        }
    }

    // --- 辅助方法 ---
    private void checkAndRespawnExits() {
        long count = exitRepository.count();
        if (count < 5) {
            for (int i = 0; i < 5 - count; i++) spawnNewExit();
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
        long count = agentRepository.count();
        if (count < 5) {
            for(int i=0; i < 5 - count; i++) spawnNewAgent();
        }
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

    // 🔥 核心改进：彻底防止边缘卡死
    private void forceSmartRandomMove(Agent agent, List<Agent> allAgents) {
        int currentX = agent.getX();
        int currentY = agent.getY();

        // 0:UP, 1:DOWN, 2:LEFT, 3:RIGHT
        int[] dx = {0, 0, -1, 1};
        int[] dy = {-1, 1, 0, 0};

        List<Integer> directions = new ArrayList<>();

        // 🔥 彻底移除无效方向：如果在边缘，根本不把撞墙的方向加入候选列表
        if (currentY > 0) directions.add(0);  // UP (只有不在最上面才能往上)
        if (currentY < 19) directions.add(1); // DOWN
        if (currentX > 0) directions.add(2);  // LEFT
        if (currentX < 19) directions.add(3); // RIGHT

        // 打乱顺序，实现随机选择
        Collections.shuffle(directions);

        // 尝试移动
        for (int dirIndex : directions) {
            int tryX = currentX + dx[dirIndex];
            int tryY = currentY + dy[dirIndex];

            if (isValidMove(tryX, tryY) && !isOccupied(tryX, tryY, allAgents)) {
                agent.setX(tryX);
                agent.setY(tryY);
                log("🔀 " + agent.getName() + " 自动修正路线 (" + tryX + "," + tryY + ")");
                return;
            }
        }
    }
}