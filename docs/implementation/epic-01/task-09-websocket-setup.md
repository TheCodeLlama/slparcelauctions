# Task 01-09: WebSocket STOMP Configuration

> **Before starting:** read [CONVENTIONS.md](../CONVENTIONS.md) for project-wide rules (no new migrations, Lombok required, vertical slices, feature-based packages).

## Goal

Set up WebSocket support with STOMP over SockJS so the frontend can subscribe to real-time auction updates. This task wires up the infrastructure and proves it works with a simple test - actual bid broadcasting is handled in Epic 04.

## Context

Spring Boot WebSocket starter is already a dependency. This task configures the STOMP broker, sets up the frontend WebSocket client, and verifies the connection works end-to-end.

## What Needs to Happen

**Backend:**
- Configure WebSocket STOMP message broker with SockJS fallback
- Set up topic destinations for auction rooms (e.g., /topic/auction/{auctionId})
- Ensure WebSocket handshake works with JWT authentication (validate token on connect)
- Create a simple test endpoint or scheduled message that broadcasts to a test topic

**Frontend:**
- Install a STOMP client library (e.g., @stomp/stompjs)
- Create a WebSocket connection manager that:
  - Connects with JWT token
  - Handles reconnection on disconnect
  - Provides subscribe/unsubscribe methods for auction topics
- Build a simple test component (can be on a /dev/ws-test page) that subscribes to a topic and displays received messages

## Acceptance Criteria

- WebSocket connection establishes between frontend and backend
- Frontend can subscribe to a topic and receive messages
- Connection requires a valid JWT (unauthenticated connections are rejected)
- Reconnection works after a brief disconnection
- The test topic proves messages flow from backend to frontend in real-time

## Notes

- This is infrastructure setup only. The actual bid broadcasting, auction room subscriptions, and countdown sync are Epic 04 tasks.
- The test page/component can be removed or hidden behind a dev flag later.
- Consider using SockJS as a fallback for browsers that don't support native WebSocket.
- STOMP topic naming convention: `/topic/auction/{id}` for per-auction updates.
