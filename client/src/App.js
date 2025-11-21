import React, { useState, useEffect, useRef } from 'react';
import axios from 'axios';
import './App.css';

function App() {
    // 初始状态：包含 agent, 资源, 出口, 日志
    const [gameState, setGameState] = useState({
        agents: [],
        resources: [],
        exits: [],
        logs: []
    });

    const logContainerRef = useRef(null); // 用于日志自动滚动
    const gridSize = 20; // 20x20 网格

    // 每 1 秒轮询后端接口
    useEffect(() => {
        const fetchData = async () => {
            try {
                // 确保后端地址正确
                // const response = await axios.get('http://localhost:8080/api/gamestate');
                const response = await axios.get('/api/gamestate');
                setGameState(response.data);
            } catch (error) {
                console.error("Error fetching game state:", error);
            }
        };

        fetchData(); // 立即执行一次
        const interval = setInterval(fetchData, 1000); // 每秒刷新
        return () => clearInterval(interval);
    }, []);

    // 当日志更新时，自动滚动到底部
    useEffect(() => {
        if (logContainerRef.current) {
            logContainerRef.current.scrollTop = logContainerRef.current.scrollHeight;
        }
    }, [gameState.logs]);

    // 辅助函数：根据日志内容返回对应的 class
    const getLogClass = (log) => {
        if (log.includes("🎉")) return "log-entry log-harvest";
        if (log.includes("💀")) return "log-entry log-death";
        if (log.includes("🔨")) return "log-entry log-craft";
        if (log.includes("🚀")) return "log-entry log-escape";
        if (log.includes("🍞")) return "log-entry log-eat";
        if (log.includes("⚠️")) return "log-entry log-warn";
        return "log-entry";
    };

    // 核心渲染逻辑：决定每个格子显示什么
    const renderCell = (x, y) => {
        // 1. 渲染 Agent (优先级最高，覆盖在最上层)
        const agent = gameState.agents.find(a => a.x === x && a.y === y && a.isAlive);
        if (agent) {
            // 检查是否持有斧头 (Axe)
            const hasAxe = agent.inventory && agent.inventory.Axe > 0;
            return (
                <div className={`cell-content agent ${hasAxe ? 'armed' : ''}`} title={`Agent: ${agent.name}\nHP: ${agent.lifespan}`}>
                    {/* 持有斧头显示 🪓，否则显示 🤖 */}
                    {hasAxe ? '🪓' : '🤖'}
                    <span className="agent-hp">{agent.lifespan}</span>
                </div>
            );
        }

        // 2. 渲染出口 (Exit) - 优先级第二
        const exit = gameState.exits && gameState.exits.find(e => e.x === x && e.y === y);
        if (exit) {
            return <div className="cell-content exit" title="EXIT">🚪</div>;
        }

        // 3. 渲染资源 (Resource) - 优先级最低
        const resource = gameState.resources.find(r => r.x === x && r.y === y);
        if (resource) {
            return (
                <div className="cell-content resource" title={resource.type}>
                    {resource.type === 'Wheat' ? '🌾' : '🪨'}
                </div>
            );
        }

        // 空格子
        return null;
    };

    // 生成网格数组
    const grid = [];
    for (let y = 0; y < gridSize; y++) {
        for (let x = 0; x < gridSize; x++) {
            grid.push(
                <div key={`${x}-${y}`} className="grid-cell">
                    {renderCell(x, y)}
                </div>
            );
        }
    }

    return (
        <div className="App">
            <h1>The Living Grid 🌍</h1>

            <div className="main-layout">

                {/* 左侧：游戏区域 */}
                <div className="game-section">
                    <div className="stats-bar">
                        <span className="stat-item">🤖 Agents: {gameState.agents.filter(a => a.isAlive).length}</span>
                        <span className="stat-item">🌱 Resources: {gameState.resources.length}</span>
                        <span className="stat-item">🚪 Exits: {gameState.exits ? gameState.exits.length : 0}</span>
                    </div>

                    <div className="grid-board">
                        {grid}
                    </div>
                </div>

                {/* 右侧：日志控制台 */}
                <div className="log-section">
                    <h3>System Logs</h3>
                    <div className="log-container" ref={logContainerRef}>
                        {gameState.logs && gameState.logs.length > 0 ? (
                            gameState.logs.map((log, index) => (
                                <div key={index} className={getLogClass(log)}>
                                    {log}
                                </div>
                            ))
                        ) : (
                            <div className="log-entry waiting">Waiting for server logs...</div>
                        )}
                    </div>
                </div>

            </div>
        </div>
    );
}

export default App;