import { useEffect, useEffectEvent, useRef, useState } from 'react'
import './App.css'

interface NodeStatus {
  nodeId: string
  status: 'ONLINE' | 'OFFLINE'
  timestamp: number
}

interface ScheduleEvent {
  jobId: string
  nodeId: string
  action: string
  timestamp: number
}

interface Alert {
  severity: 'INFO' | 'WARNING' | 'CRITICAL'
  message: string
  timestamp: number
}

interface DashboardState {
  connected: boolean
  onlineNodes: number
  nodes: Map<string, NodeStatus>
  events: ScheduleEvent[]
  alerts: Alert[]
}

function App() {
  const [state, setState] = useState<DashboardState>({
    connected: false,
    onlineNodes: 0,
    nodes: new Map(),
    events: [],
    alerts: []
  })

  const wsRef = useRef<WebSocket | null>(null)
  const reconnectTimerRef = useRef<number | null>(null)

  const handleMessage = useEffectEvent((event: MessageEvent<string>) => {
    const data = JSON.parse(event.data)

    switch (data.type) {
      case 'CONNECTED':
        console.log('✅', data.message)
        break
      case 'STATUS':
        setState(prev => ({ ...prev, onlineNodes: data.onlineNodes }))
        break
      case 'NODE_STATUS':
        setState(prev => {
          const nodes = new Map(prev.nodes)
          nodes.set(data.nodeId, {
            nodeId: data.nodeId,
            status: data.status,
            timestamp: data.timestamp
          })
          return { ...prev, nodes }
        })
        break
      case 'SCHEDULE_EVENT':
        setState(prev => ({
          ...prev,
          events: [data, ...prev.events].slice(0, 50)
        }))
        break
      case 'ALERT':
        setState(prev => ({
          ...prev,
          alerts: [data, ...prev.alerts].slice(0, 20)
        }))
        break
    }
  })

  useEffect(() => {
    let disposed = false

    const clearReconnectTimer = () => {
      if (reconnectTimerRef.current !== null) {
        window.clearTimeout(reconnectTimerRef.current)
        reconnectTimerRef.current = null
      }
    }

    const connectWebSocket = () => {
      if (disposed) {
        return
      }

      const websocket = new WebSocket('ws://localhost:8080/ws/dashboard')
      wsRef.current = websocket

      websocket.onopen = () => {
        console.log('🌐 Connected to Dashboard WebSocket')
        setState(prev => ({ ...prev, connected: true }))
        websocket.send('GET_STATUS')
      }

      websocket.onclose = () => {
        console.log('🔌 Disconnected from Dashboard WebSocket')
        setState(prev => ({ ...prev, connected: false }))

        if (wsRef.current === websocket) {
          wsRef.current = null
        }

        if (!disposed) {
          clearReconnectTimer()
          reconnectTimerRef.current = window.setTimeout(connectWebSocket, 3000)
        }
      }

      websocket.onmessage = handleMessage
    }

    connectWebSocket()

    return () => {
      disposed = true
      clearReconnectTimer()
      const websocket = wsRef.current
      wsRef.current = null
      websocket?.close()
    }
  }, [])

  return (
    <div className="dashboard">
      <header className="dashboard-header">
        <h1>🚀 Hyperscale LCM Dashboard</h1>
        <div className={`connection-status ${state.connected ? 'online' : 'offline'}`}>
          {state.connected ? '🟢 Connected' : '🔴 Disconnected'}
        </div>
      </header>

      <main className="dashboard-content">
        {/* 概览卡片 */}
        <section className="overview-cards">
          <div className="card">
            <h3>📊 在线节点</h3>
            <div className="card-value">{state.onlineNodes}</div>
          </div>
          <div className="card">
            <h3>🔄 调度事件</h3>
            <div className="card-value">{state.events.length}</div>
          </div>
          <div className="card">
            <h3>⚠️ 活跃告警</h3>
            <div className="card-value alert-count">{state.alerts.length}</div>
          </div>
        </section>

        {/* 节点列表 */}
        <section className="node-list">
          <h2>🖥️ 节点状态</h2>
          <div className="node-grid">
            {Array.from(state.nodes.values()).map(node => (
              <div key={node.nodeId} className={`node-card ${node.status.toLowerCase()}`}>
                <div className="node-id">{node.nodeId.slice(0, 8)}...</div>
                <div className={`node-status ${node.status.toLowerCase()}`}>
                  {node.status === 'ONLINE' ? '🟢' : '🔴'} {node.status}
                </div>
              </div>
            ))}
            {state.nodes.size === 0 && (
              <div className="empty-state">暂无节点数据</div>
            )}
          </div>
        </section>

        {/* 调度事件流 */}
        <section className="event-stream">
          <h2>📋 调度事件</h2>
          <div className="event-list">
            {state.events.map((event, idx) => (
              <div key={idx} className="event-item">
                <span className="event-time">
                  {new Date(event.timestamp).toLocaleTimeString()}
                </span>
                <span className="event-action">{event.action}</span>
                <span className="event-job">Job: {event.jobId}</span>
                <span className="event-node">→ Node: {event.nodeId}</span>
              </div>
            ))}
            {state.events.length === 0 && (
              <div className="empty-state">暂无调度事件</div>
            )}
          </div>
        </section>

        {/* 告警面板 */}
        <section className="alert-panel">
          <h2>🚨 告警中心</h2>
          <div className="alert-list">
            {state.alerts.map((alert, idx) => (
              <div key={idx} className={`alert-item ${alert.severity.toLowerCase()}`}>
                <span className="alert-severity">{alert.severity}</span>
                <span className="alert-message">{alert.message}</span>
                <span className="alert-time">
                  {new Date(alert.timestamp).toLocaleTimeString()}
                </span>
              </div>
            ))}
            {state.alerts.length === 0 && (
              <div className="empty-state">✅ 系统运行正常</div>
            )}
          </div>
        </section>
      </main>

      <footer className="dashboard-footer">
        <p>Hyperscale LCM © 2026 | Powered by Antigravity AI</p>
      </footer>
    </div>
  )
}

export default App
