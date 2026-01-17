import React, { createContext, useContext, useState, useCallback, useMemo } from 'react';
import { useWebSocket, type WebSocketMessage } from '../hooks/useWebSocket';

interface Alert {
    id: string;
    severity: string;
    message: string;
    timestamp: number;
}

interface WebSocketContextValue {
    isConnected: boolean;
    onlineNodes: number;
    alerts: Alert[];
    lastEvent: WebSocketMessage | null;
    clearAlerts: () => void;
    dismissAlert: (id: string) => void;
}

const WebSocketContext = createContext<WebSocketContextValue | null>(null);

export const WebSocketProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
    const [onlineNodes, setOnlineNodes] = useState(0);
    const [alerts, setAlerts] = useState<Alert[]>([]);
    const [lastEvent, setLastEvent] = useState<WebSocketMessage | null>(null);

    const handleMessage = useCallback((message: WebSocketMessage) => {
        switch (message.type) {
            case 'STATUS':
                if (message.onlineNodes !== undefined) {
                    setOnlineNodes(message.onlineNodes);
                }
                break;
            case 'NODE_STATUS':
            case 'SCHEDULE_EVENT':
                setLastEvent(message);
                break;
            case 'ALERT':
                setAlerts((prev) => [
                    {
                        id: `${Date.now()}-${Math.random()}`,
                        severity: message.severity || 'INFO',
                        message: message.message || '',
                        timestamp: message.timestamp || Date.now(),
                    },
                    ...prev,
                ].slice(0, 50)); // Keep only last 50 alerts
                break;
        }
    }, []);

    const { isConnected } = useWebSocket({
        url: 'ws://localhost:8080/ws/dashboard',
        onMessage: handleMessage,
        onConnect: () => console.log('Dashboard WebSocket connected'),
        onDisconnect: () => console.log('Dashboard WebSocket disconnected'),
    });

    const clearAlerts = useCallback(() => {
        setAlerts([]);
    }, []);

    const dismissAlert = useCallback((id: string) => {
        setAlerts((prev) => prev.filter((alert) => alert.id !== id));
    }, []);

    const value = useMemo(() => ({
        isConnected,
        onlineNodes,
        alerts,
        lastEvent,
        clearAlerts,
        dismissAlert,
    }), [isConnected, onlineNodes, alerts, lastEvent, clearAlerts, dismissAlert]);

    return (
        <WebSocketContext.Provider value={value}>
            {children}
        </WebSocketContext.Provider>
    );
};

export function useWebSocketContext(): WebSocketContextValue {
    const context = useContext(WebSocketContext);
    if (!context) {
        throw new Error('useWebSocketContext must be used within a WebSocketProvider');
    }
    return context;
}
