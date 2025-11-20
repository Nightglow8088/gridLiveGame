import React, { useState, useEffect, useRef } from 'react';
import axios from 'axios';
import './App.css';

function App() {
    // 1. 初始状态增加 logs: []
    const [gameState, setGameState] = useState({ agents: [], resources: [], logs: [] });
    const logContainerRef = useRef(null); // 用来自动滚动日志到底部
    const gridSize = 20;

    useEffect(() => {
        const fetchData = async () => {
            try {
                const response = await axios.get('http://localhost:8080/api/gamestate');
                setGameState(response.data);
            } catch (error) {
                console.error("Error fetching game state:", error);
            }
        };
        fetchData();
        const interval = setInterval(fetchData, 1000);
        return () => clearInterval(interval);
    }, []);

    // 2. 自动滚动日志
    useEffect(() => {
        if (logContainerRef.current) {
            logContainerRef.current.scrollTop = logContainerRef.current.scrollHeight;
        }
    }, [gameState.logs]);

    const renderCell = (x, y) => {
        const agent = gameState.agents.find(a => a.x === x && a.y === y && a.isAlive);
        if (agent) {
            return (
                <div className="cell-content agent">
                    🤖<span className="agent-hp">{agent.lifespan}</span>
                </div>
            );
        }
        const resource = gameState.resources.find(r => r.x === x && r.y === y);
        if (resource) {
            return <div className="cell-content resource">{resource.type === 'Wheat' ? '🌾' : '🪨'}</div>;
        }
        return null;
    };

    const grid = [];
    for (let y = 0; y < gridSize; y++) {
        for (let x = 0; x < gridSize; x++) {
            grid.push(<div key={`${x}-${y}`} className="grid-cell">{renderCell(x, y)}</div>);
        }
    }

    // 3. 布局修改：左边是网格，右边是日志面板
    return (
        <div className="App">
            <h1>The Living Grid </h1>

            <div className="main-container" style={{ display: 'flex', gap: '20px' }}>

                {/* 左侧：游戏网格 */}
                <div className="game-panel">
                    <div className="stats-panel">
                        <span>Agents: {gameState.agents.filter(a => a.isAlive).length}</span>
                        <span> | Resources: {gameState.resources.length}</span>
                    </div>
                    <div className="grid-container">
                        {grid}
                    </div>
                </div>

                {/* 右侧：日志控制台 */}
                <div className="log-panel" style={{
                    width: '300px',
                    height: '640px',
                    backgroundColor: '#1e1e1e',
                    color: '#00ff00',
                    padding: '10px',
                    borderRadius: '8px',
                    fontFamily: 'monospace',
                    overflowY: 'auto',
                    textAlign: 'left',
                    fontSize: '12px'
                }} ref={logContainerRef}>
                    <h3>System Logs</h3>
                    {gameState.logs && gameState.logs.map((log, index) => (
                        <div key={index} style={{ marginBottom: '4px', borderBottom: '1px solid #333' }}>
                            {log}
                        </div>
                    ))}
                </div>

            </div>
        </div>
    );
}

export default App;