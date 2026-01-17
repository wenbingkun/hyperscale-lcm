import { useEffect, useRef, useState, useCallback } from 'react';

export type WebSocketMessageType =
    | 'CONNECTED'
    | 'PONG'
    | 'STATUS'
    | 'NODE_STATUS'
    | 'SCHEDULE_EVENT'
    | 'ALERT';

export interface WebSocketMessage {
    type: WebSocketMessageType;
    message?: string;
    nodeId?: string;
    jobId?: string;
    action?: string;
    status?: string;
    severity?: string;
    onlineNodes?: number;
    timestamp?: number;
}

export interface UseWebSocketOptions {
    url: string;
    onMessage?: (message: WebSocketMessage) => void;
    onConnect?: () => void;
    onDisconnect?: () => void;
    reconnectAttempts?: number;
    reconnectInterval?: number;
}

export interface UseWebSocketReturn {
    isConnected: boolean;
    lastMessage: WebSocketMessage | null;
    sendMessage: (message: string) => void;
    connect: () => void;
    disconnect: () => void;
}

export function useWebSocket(options: UseWebSocketOptions): UseWebSocketReturn {
    const {
        url,
        onMessage,
        onConnect,
        onDisconnect,
        reconnectAttempts = 5,
        reconnectInterval = 3000,
    } = options;

    const [isConnected, setIsConnected] = useState(false);
    const [lastMessage, setLastMessage] = useState<WebSocketMessage | null>(null);
    const wsRef = useRef<WebSocket | null>(null);
    const reconnectCountRef = useRef(0);
    const reconnectTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);

    const connect = useCallback(() => {
        if (wsRef.current?.readyState === WebSocket.OPEN) {
            return;
        }

        try {
            wsRef.current = new WebSocket(url);

            wsRef.current.onopen = () => {
                console.log('🌐 WebSocket connected');
                setIsConnected(true);
                reconnectCountRef.current = 0;
                onConnect?.();
            };

            wsRef.current.onclose = () => {
                console.log('🔌 WebSocket disconnected');
                setIsConnected(false);
                onDisconnect?.();

                // Auto reconnect
                if (reconnectCountRef.current < reconnectAttempts) {
                    reconnectCountRef.current += 1;
                    console.log(`🔄 Reconnecting... (${reconnectCountRef.current}/${reconnectAttempts})`);
                    reconnectTimeoutRef.current = setTimeout(connect, reconnectInterval);
                }
            };

            wsRef.current.onerror = (error) => {
                console.error('❌ WebSocket error:', error);
            };

            wsRef.current.onmessage = (event) => {
                try {
                    const message: WebSocketMessage = JSON.parse(event.data);
                    setLastMessage(message);
                    onMessage?.(message);
                } catch (e) {
                    console.error('Failed to parse WebSocket message:', e);
                }
            };
        } catch (error) {
            console.error('Failed to create WebSocket:', error);
        }
    }, [url, onMessage, onConnect, onDisconnect, reconnectAttempts, reconnectInterval]);

    const disconnect = useCallback(() => {
        if (reconnectTimeoutRef.current) {
            clearTimeout(reconnectTimeoutRef.current);
        }
        reconnectCountRef.current = reconnectAttempts; // Prevent auto-reconnect
        wsRef.current?.close();
    }, [reconnectAttempts]);

    const sendMessage = useCallback((message: string) => {
        if (wsRef.current?.readyState === WebSocket.OPEN) {
            wsRef.current.send(message);
        } else {
            console.warn('WebSocket is not connected');
        }
    }, []);

    useEffect(() => {
        connect();
        return () => {
            disconnect();
        };
    }, [connect, disconnect]);

    // Ping to keep connection alive
    useEffect(() => {
        if (!isConnected) return;

        const pingInterval = setInterval(() => {
            sendMessage('PING');
        }, 30000);

        return () => clearInterval(pingInterval);
    }, [isConnected, sendMessage]);

    return {
        isConnected,
        lastMessage,
        sendMessage,
        connect,
        disconnect,
    };
}
