import { useEffect, useRef, useState, useCallback } from 'react';

export type WebSocketMessageType =
    | 'CONNECTED'
    | 'PONG'
    | 'STATUS'
    | 'NODE_STATUS'
    | 'HEARTBEAT_UPDATE'
    | 'JOB_STATUS'
    | 'SCHEDULE_EVENT'
    | 'DISCOVERY_EVENT'
    | 'ALERT';

export interface WebSocketMessage {
    type: WebSocketMessageType;
    payload?: Record<string, unknown>; // Generic payload to hold event-specific data
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

    // Store callbacks and config in refs, synced via useEffect
    const onMessageRef = useRef(onMessage);
    const onConnectRef = useRef(onConnect);
    const onDisconnectRef = useRef(onDisconnect);
    const reconnectAttemptsRef = useRef(reconnectAttempts);
    const reconnectIntervalRef = useRef(reconnectInterval);
    const connectImplRef = useRef<(() => void) | undefined>(undefined);

    useEffect(() => {
        onMessageRef.current = onMessage;
        onConnectRef.current = onConnect;
        onDisconnectRef.current = onDisconnect;
        reconnectAttemptsRef.current = reconnectAttempts;
        reconnectIntervalRef.current = reconnectInterval;
    });

    const connect = useCallback(() => {
        connectImplRef.current?.();
    }, []);

    // Keep the actual implementation in a ref to avoid self-reference issues
    useEffect(() => {
        connectImplRef.current = () => {
            if (wsRef.current?.readyState === WebSocket.OPEN) {
                return;
            }

            try {
                wsRef.current = new WebSocket(url);

                wsRef.current.onopen = () => {
                    console.log('🌐 WebSocket connected');
                    setIsConnected(true);
                    reconnectCountRef.current = 0;
                    onConnectRef.current?.();
                };

                wsRef.current.onclose = () => {
                    console.log('🔌 WebSocket disconnected');
                    setIsConnected(false);
                    onDisconnectRef.current?.();

                    // Auto reconnect
                    if (reconnectCountRef.current < reconnectAttemptsRef.current) {
                        reconnectCountRef.current += 1;
                        console.log(`🔄 Reconnecting... (${reconnectCountRef.current}/${reconnectAttemptsRef.current})`);
                        reconnectTimeoutRef.current = setTimeout(() => connectImplRef.current?.(), reconnectIntervalRef.current);
                    }
                };

                wsRef.current.onerror = (error) => {
                    console.error('❌ WebSocket error:', error);
                };

                wsRef.current.onmessage = (event) => {
                    try {
                        const message: WebSocketMessage = JSON.parse(event.data);
                        setLastMessage(message);
                        onMessageRef.current?.(message);
                    } catch (e) {
                        console.error('Failed to parse WebSocket message:', e);
                    }
                };
            } catch (error) {
                console.error('Failed to create WebSocket:', error);
            }
        };
    }, [url]);

    const disconnect = useCallback(() => {
        if (reconnectTimeoutRef.current) {
            clearTimeout(reconnectTimeoutRef.current);
        }
        reconnectCountRef.current = reconnectAttemptsRef.current; // Prevent auto-reconnect
        wsRef.current?.close();
    }, []);

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
