import React, { useState, useEffect, useRef } from 'react';
import axios from 'axios';

// --- 1. 全局样式 (动画、滚动条) ---
const globalStyles = `
  body { margin: 0; padding: 0; background: #1a1a1a; font-family: 'Segoe UI', sans-serif; }
  ::-webkit-scrollbar { width: 8px; }
  ::-webkit-scrollbar-track { background: #0f0f0f; }
  ::-webkit-scrollbar-thumb { background: #333; border-radius: 4px; }
  ::-webkit-scrollbar-thumb:hover { background: #555; }
  
  @keyframes popIn {
    from { transform: scale(0); opacity: 0; }
    to { transform: scale(1); opacity: 1; }
  }
  @keyframes pulse {
    0% { transform: scale(1); filter: drop-shadow(0 0 0 rgba(0,255,0,0.4)); }
    50% { transform: scale(1.1); filter: drop-shadow(0 0 5px rgba(0,255,0,0.8)); }
    100% { transform: scale(1); filter: drop-shadow(0 0 0 rgba(0,255,0,0.4)); }
  }
`;

// --- 2. 组件内联样式对象 ---
const styles = {
    app: {
        textAlign: 'center',
        backgroundColor: '#1a1a1a',
        minHeight: '100vh',
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        color: 'white',
    },
    title: {
        marginBottom: '20px',
        fontSize: '2.5rem',
        color: '#e0e0e0',
        textShadow: '0 2px 4px rgba(0,0,0,0.5)',
    },
    mainLayout: {
        display: 'flex',
        gap: '30px',
        padding: '20px',
        backgroundColor: '#2d2d2d',
        borderRadius: '15px',
        boxShadow: '0 10px 30px rgba(0,0,0,0.5)',
    },
    // 左侧游戏区
    gameSection: {
        display: 'flex',
        flexDirection: 'column',
        gap: '15px',
    },
    statsBar: {
        display: 'flex',
        justifyContent: 'space-around',
        backgroundColor: '#3d3d3d',
        padding: '10px',
        borderRadius: '8px',
        fontWeight: 'bold',
        boxShadow: 'inset 0 2px 5px rgba(0,0,0,0.2)',
    },
    statItem: { color: '#a0a0a0' },

    // 网格容器
    gridBoard: {
        display: 'grid',
        gridTemplateColumns: 'repeat(20, 25px)', // 20列，每列25px
        gridTemplateRows: 'repeat(20, 25px)',    // 20行，每行25px
        gap: '2px',
        backgroundColor: '#111',
        padding: '10px', // 注意：这里的padding会影响绝对定位的偏移量
        borderRadius: '8px',
        border: '2px solid #444',
        position: 'relative', // 【关键】设置为相对定位，作为悬浮Agent的坐标原点
    },
    // 单个格子
    gridCell: {
        width: '25px',
        height: '25px',
        backgroundColor: '#2a2a2a',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        fontSize: '16px',
        borderRadius: '2px',
        position: 'relative',
    },
    // 实体样式
    cellContent: {
        width: '100%', height: '100%', display: 'flex', alignItems: 'center', justifyContent: 'center', position: 'relative'
    },
    // 【新增】悬浮Agent样式 (解决瞬移问题)
    floatingAgent: {
        position: 'absolute',
        width: '25px',
        height: '25px',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        borderRadius: '50%',
        transition: 'all 0.5s ease-in-out', // 【核心】平滑移动动画
        zIndex: 100, // 保证在最上层
        // left 和 top 由代码动态计算
    },

    agent: { backgroundColor: 'rgba(0, 191, 255, 0.2)', borderRadius: '50%' },
    agentArmed: { backgroundColor: 'rgba(255, 69, 0, 0.3)', boxShadow: '0 0 8px rgba(255, 69, 0, 0.6)', transform: 'scale(1.1)', zIndex: 10 },
    agentHp: {
        position: 'absolute', bottom: '-2px', right: '-4px', fontSize: '7px',
        backgroundColor: '#ff3333', color: 'white', padding: '0 2px', borderRadius: '3px', zIndex: 20
    },
    resource: { animation: 'popIn 0.3s cubic-bezier(0.175, 0.885, 0.32, 1.275)' },
    exit: { backgroundColor: 'rgba(0, 255, 0, 0.1)', fontSize: '18px', animation: 'pulse 2s infinite' },

    // 右侧日志区
    logSection: {
        width: '320px',
        height: '600px',
        backgroundColor: '#0f0f0f',
        borderRadius: '8px',
        border: '1px solid #333',
        display: 'flex',
        flexDirection: 'column',
        overflow: 'hidden',
        fontFamily: "'Consolas', 'Monaco', monospace",
    },
    logHeader: {
        backgroundColor: '#1f1f1f', margin: 0, padding: '10px', fontSize: '1rem',
        color: '#00ff00', borderBottom: '1px solid #333', textAlign: 'left'
    },
    logContainer: {
        flex: 1, overflowY: 'auto', padding: '10px', textAlign: 'left',
        fontSize: '12px', color: '#cccccc', scrollBehavior: 'smooth'
    },
    logEntry: { marginBottom: '4px', paddingBottom: '4px', borderBottom: '1px solid #222', lineHeight: '1.4', wordBreak: 'break-word' },
};

// --- 3. 日志颜色配置 ---
const logColors = {
    harvest: '#ffd700', // 金色
    death: '#ff4444',   // 红色
    craft: '#00bfff',   // 蓝色
    escape: '#00ff00',  // 绿色
    eat: '#ffcc99',     // 小麦色
    warn: '#ff8800',    // 橙色
    move: '#808080',    // 灰色
    default: '#cccccc'
};

function App() {
    const [gameState, setGameState] = useState({
        agents: [], resources: [], exits: [], logs: []
    });

    const logContainerRef = useRef(null);
    const gridSize = 20;

    // 1. 轮询后端数据
    useEffect(() => {
        const fetchData = async () => {
            try {
                const response = await axios.get('/api/gamestate');
                setGameState(response.data);
            } catch (error) {
                console.error("Error fetching game state:", error);
            }
        };

        fetchData();
        const interval = setInterval(fetchData, 500); // 500ms 刷新一次
        return () => clearInterval(interval);
    }, []);

    // 2. 日志自动滚动
    useEffect(() => {
        if (logContainerRef.current) {
            logContainerRef.current.scrollTop = logContainerRef.current.scrollHeight;
        }
    }, [gameState.logs]);

    // 3. 获取日志样式
    const getLogStyle = (log) => {
        let color = logColors.default;
        let fontWeight = 'normal';

        if (log.includes("🎉")) color = logColors.harvest;
        else if (log.includes("💀")) color = logColors.death;
        else if (log.includes("🔨")) color = logColors.craft;
        else if (log.includes("🚀")) { color = logColors.escape; fontWeight = 'bold'; }
        else if (log.includes("🍞")) color = logColors.eat;
        else if (log.includes("⚠️")) color = logColors.warn;
        else if (log.includes("🏃")) color = logColors.move;

        return { ...styles.logEntry, color, fontWeight };
    };

    // 4. 渲染格子 (只负责渲染 资源 和 出口，Agent 移交给悬浮层)
    const renderCell = (x, y) => {
        // A. Exit (出口)
        const exit = gameState.exits && gameState.exits.find(e => e.x === x && e.y === y);
        if (exit) {
            return <div style={{...styles.cellContent, ...styles.exit}} title="EXIT">🚪</div>;
        }

        // B. Resource (资源)
        const resource = gameState.resources.find(r => r.x === x && r.y === y);
        if (resource) {
            return (
                <div style={{...styles.cellContent, ...styles.resource}} title={resource.type}>
                    {resource.type === 'Wheat' ? '🌾' : '🪨'}
                </div>
            );
        }

        return null;
    };

    // 生成静态网格背景
    const grid = [];
    for (let y = 0; y < gridSize; y++) {
        for (let x = 0; x < gridSize; x++) {
            grid.push(
                <div key={`${x}-${y}`} style={styles.gridCell}>
                    {renderCell(x, y)}
                </div>
            );
        }
    }

    return (
        <div style={styles.app}>
            {/* 注入全局动画样式 */}
            <style>{globalStyles}</style>

            <h1 style={styles.title}>The Living Grid 🌍</h1>

            <div style={styles.mainLayout}>
                {/* 左侧：游戏区域 */}
                <div style={styles.gameSection}>
                    <div style={styles.statsBar}>
                        <span style={styles.statItem}>🤖 Agents: {gameState.agents.filter(a => a.isAlive).length}</span>
                        <span style={styles.statItem}>🌱 Resources: {gameState.resources.length}</span>
                        <span style={styles.statItem}>🚪 Exits: {gameState.exits ? gameState.exits.length : 0}</span>
                    </div>

                    <div style={styles.gridBoard}>
                        {/* 1. 渲染基础网格 */}
                        {grid}

                        {/* 2. 【新增】悬浮渲染 Agents 层 */}
                        {gameState.agents.filter(a => a.isAlive).map(agent => {
                            // 动态计算位置：Padding(10) + 坐标 * (Size(25) + Gap(2))
                            const leftPos = 10 + agent.x * 27;
                            const topPos = 10 + agent.y * 27;

                            const hasAxe = agent.inventory && agent.inventory.Axe > 0;

                            // 合并样式：基础悬浮 + Agent类型 + 武器发光 + 坐标定位
                            const agentFinalStyle = {
                                ...styles.floatingAgent,
                                ...styles.agent,
                                ...(hasAxe ? styles.agentArmed : {}),
                                left: `${leftPos}px`,
                                top: `${topPos}px`,
                            };

                            return (
                                <div key={agent.id} style={agentFinalStyle} title={`Agent: ${agent.name}\nHP: ${agent.lifespan}`}>
                                    {hasAxe ? '🪓' : '🤖'}
                                    <span style={styles.agentHp}>{agent.lifespan}</span>
                                </div>
                            );
                        })}
                    </div>
                </div>

                {/* 右侧：日志控制台 */}
                <div style={styles.logSection}>
                    <h3 style={styles.logHeader}>System Logs</h3>
                    <div style={styles.logContainer} ref={logContainerRef}>
                        {gameState.logs && gameState.logs.length > 0 ? (
                            gameState.logs.map((log, index) => (
                                <div key={index} style={getLogStyle(log)}>
                                    {log}
                                </div>
                            ))
                        ) : (
                            <div style={{...styles.logEntry, color: '#666', textAlign: 'center', marginTop: '20px'}}>
                                Waiting for server logs...
                            </div>
                        )}
                    </div>
                </div>

            </div>
        </div>
    );
}

export default App;