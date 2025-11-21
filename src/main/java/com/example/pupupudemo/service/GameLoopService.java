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

    // 日志队列 (线程安全)
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

    // 每 2 秒执行一次
    @Scheduled(fixedRate = 2000)
    public void runGameTurn() {
        log("----- 新回合开始 -----");
        int hpCost = liveDataService.getHpCostPerTurn();

        // 0. 维护世界
        checkAndRespawnExits();

        List<Agent> agents = agentRepository.findAll();
        List<WorldResource> allResources = resourceRepository.findAll();
        // 获取所有出口 (转为线程安全列表)
        List<WorldExit> allExits = Collections.synchronizedList(new ArrayList<>(exitRepository.findAll()));

        // 线程安全的列表，用于收集结果
        List<Agent> agentsToSave = Collections.synchronizedList(new ArrayList<>());
        List<Agent> agentsToDelete = Collections.synchronizedList(new ArrayList<>());

        // 🔥 并行处理所有 Agent
        agents.parallelStream().forEach(agent -> {
            boolean hasAxe = agent.getInventory().getOrDefault("Axe", 0) > 0;

            // =================================================
            // 1. 统一生存逻辑
            // =================================================
            int wheatCount = agent.getInventory().getOrDefault("Wheat", 0);
            if (wheatCount > 0) {
                agent.addResource("Wheat", -1);
                // 吃东西回血，上限100
                agent.setLifespan(Math.min(agent.getLifespan() + 5, 100));
                log("🍞 " + agent.getName() + " 进食 (HP=" + agent.getLifespan() + ")");
            } else {
                agent.setLifespan(agent.getLifespan() - hpCost);
            }

            if (agent.getLifespan() <= 0) {
                log("💀 " + agent.getName() + (hasAxe ? " 带着斧头遗憾离世。" : " 饿死了。"));
                agentsToDelete.add(agent);
                return; // 结束当前 Agent 逻辑
            }

            // =================================================
            // 2. 视野逻辑 (Vision)
            // =================================================
            List<String> visibleItems = new ArrayList<>();
            final WorldResource[] resourceUnderFeetWrapper = {null};

            // A. 找资源
            for (WorldResource res : allResources) {
                int dx = Math.abs(res.getX() - agent.getX());
                int dy = Math.abs(res.getY() - agent.getY());
                if (dx == 0 && dy == 0) {
                    visibleItems.add("STANDING_ON RESOURCE " + res.getType());
                    resourceUnderFeetWrapper[0] = res;
                } else if (dx <= 1 && dy <= 1) {
                    visibleItems.add(res.getType() + " at (" + res.getX() + "," + res.getY() + ")");
                }
            }

            // B. 找出口 (🔥 优化：没斧头时稍微“屏蔽”远处出口，防止诱惑)
            int visionRange = hasAxe ? 5 : 1;
            synchronized (allExits) {
                for (WorldExit exit : allExits) {
                    int dx = Math.abs(exit.getX() - agent.getX());
                    int dy = Math.abs(exit.getY() - agent.getY());
                    if (dx == 0 && dy == 0) {
                        visibleItems.add("STANDING_ON EXIT");
                    } else if (dx <= visionRange && dy <= visionRange) {
                        // 只有当持有斧头，或者是贴脸(距离<=1)时，才告诉它这里有门
                        if (hasAxe || (dx <= 1 && dy <= 1)) {
                            visibleItems.add("EXIT at (" + exit.getX() + "," + exit.getY() + ")");
                        }
                    }
                }
            }

            String envDescription = visibleItems.isEmpty() ? "Nothing nearby" : "You see: " + String.join(", ", visibleItems);

            // =================================================
            // 3. 构建 Prompt (🔥 核心修改：增加优先级逻辑)
            // =================================================
            String instructions;
            if (hasAxe) {
                // 有斧头：唯一目标是逃生
                instructions = "STATE: ARMED WITH AXE. " +
                        "OBJECTIVE: ESCAPE IMMEDIATELY. " +
                        "ACTION: MOVE towards the nearest 'EXIT' coordinates. Ignore resources.";
            } else {
                // 没斧头：生存 > 采集 > 合成。严禁去出口。
                instructions = "STATE: UNARMED (No Axe). You CANNOT escape yet. " +
                        "PRIORITY ORDER: " +
                        "1. SURVIVE: If HP < 30 and you see Wheat, HARVEST it immediately. " +
                        "2. GATHER: If you see Stone, HARVEST it (Need 2 Stone to Craft Axe). " +
                        "3. CRAFT: If you have 2 Stone, CRAFT Axe. " +
                        "4. EXPLORE: Move to find resources. " +
                        "WARNING: Do NOT go to 'EXIT' locations yet, you will fail without an Axe.";
            }

            String agentState = String.format("""
                {
                    "id": "%s", "hp": %d, "inventory": %s,
                    "loc": {"x": %d, "y": %d},
                    "grid": "20x20",
                    "vision": "%s",
                    "instruction": "%s"
                }
                """, agent.getName(), agent.getLifespan(), agent.getInventory(), agent.getX(), agent.getY(), envDescription, instructions);

            try {
                // 4. AI 决策 (并行执行)
                String aiResponseJson = aiService.getAgentDecision(agentState);
                JsonNode decision = objectMapper.readTree(aiResponseJson);
                String action = decision.has("action") ? decision.get("action").asText() : "WAIT";

                // 5. 执行动作
                executeAction(agent, action, decision, resourceUnderFeetWrapper[0], agents, hasAxe);

                // =================================================
                // 6. 自动逃生检测 (需加锁)
                // =================================================
                boolean escaped = false;
                synchronized (allExits) {
                    for (WorldExit exit : allExits) {
                        if (exit.getX() == agent.getX() && exit.getY() == agent.getY() && hasAxe) {
                            log("🚀🚀🚀 " + agent.getName() + " 成功逃离矩阵！");

                            agentsToDelete.add(agent);
                            exitRepository.delete(exit);
                            allExits.remove(exit);

                            escaped = true;
                            break;
                        }
                    }
                }

                if (!escaped) {
                    agentsToSave.add(agent);
                }

            } catch (Exception e) {
                agentsToSave.add(agent); // 出错也保存(可能扣血了)
            }
        });

        // =================================================
        // 7. 批量数据库操作 (主线程)
        // =================================================
        if (!agentsToDelete.isEmpty()) {
            agentRepository.deleteAll(agentsToDelete);
        }
        if (!agentsToSave.isEmpty()) {
            agentRepository.saveAll(agentsToSave);
        }

        // 8. 生态维护
        checkAndRespawnAgents();
        checkAndRespawnResources();
        checkAndRespawnExits();

        System.out.println("----- 回合结算完成 -----\n");
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
                    // 无论是否有斧头，都记录移动日志
                    log("🏃 " + agent.getName() + " -> " + dir + " (" + newX + "," + newY + ")");
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
                // 检查是否满足合成条件
                if (agent.getInventory().getOrDefault("Stone", 0) >= 2) {
                    agent.addResource("Stone", -2);
                    agent.addResource("Axe", 1);
                    log("🔨 " + agent.getName() + " 打造出了传说之斧! 快去找出口！");
                } else {
                    // 如果 AI 乱尝试 CRAFT 但材料不够，可以选择打印日志或忽略
                    // log("⚠️ " + agent.getName() + " 试图合成斧头但石头不够。");
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

    private void forceSmartRandomMove(Agent agent, List<Agent> allAgents) {
        int currentX = agent.getX();
        int currentY = agent.getY();
        int[] dx = {0, 0, -1, 1};
        int[] dy = {-1, 1, 0, 0};
        List<Integer> directions = new ArrayList<>();

        if (currentY > 0) directions.add(0);
        if (currentY < 19) directions.add(1);
        if (currentX > 0) directions.add(2);
        if (currentX < 19) directions.add(3);

        Collections.shuffle(directions);

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