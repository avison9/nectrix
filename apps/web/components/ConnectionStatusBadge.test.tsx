import { describe, expect, it, afterEach, beforeEach, vi } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { ConnectionStatusBadge } from "./ConnectionStatusBadge";

type Listener = (event: { data?: string }) => void;

class FakeWebSocket {
  static instances: FakeWebSocket[] = [];
  url: string;
  private listeners: Record<string, Listener[]> = {};
  sentMessages: string[] = [];
  closed = false;

  constructor(url: string) {
    this.url = url;
    FakeWebSocket.instances.push(this);
  }

  addEventListener(type: string, callback: Listener) {
    (this.listeners[type] ??= []).push(callback);
  }

  send(data: string) {
    this.sentMessages.push(data);
  }

  close() {
    this.closed = true;
  }

  dispatch(type: string, event: { data?: string } = {}) {
    for (const callback of this.listeners[type] ?? []) callback(event);
  }
}

afterEach(cleanup);

describe("ConnectionStatusBadge (TICKET-110 AC4 — live connection-health badge)", () => {
  beforeEach(() => {
    FakeWebSocket.instances = [];
    process.env.NEXT_PUBLIC_CORE_APP_WS_URL = "ws://localhost:8080";
    vi.stubGlobal("WebSocket", FakeWebSocket as unknown as typeof WebSocket);
  });

  it("renders the server-provided initial status before any WS frame arrives", () => {
    render(
      <ConnectionStatusBadge
        brokerAccountId="acc-1"
        initialStatus="PENDING"
        accessToken="test-token"
      />,
    );
    expect(screen.getByText("Pending")).toBeInTheDocument();
  });

  it("sends a real subscribe frame for this brokerAccountId once the socket opens", async () => {
    render(
      <ConnectionStatusBadge
        brokerAccountId="acc-1"
        initialStatus="PENDING"
        accessToken="test-token"
      />,
    );
    const socket = FakeWebSocket.instances[0];
    expect(socket.url).toContain("access_token=test-token");
    socket.dispatch("open");

    await waitFor(() => expect(socket.sentMessages.length).toBe(1));
    const frame = JSON.parse(socket.sentMessages[0]);
    expect(frame).toEqual({
      action: "subscribe",
      channel: "broker-connection",
      brokerAccountId: "acc-1",
    });
  });

  it("updates the displayed status when a real matching event arrives", async () => {
    render(
      <ConnectionStatusBadge
        brokerAccountId="acc-1"
        initialStatus="PENDING"
        accessToken="test-token"
      />,
    );
    const socket = FakeWebSocket.instances[0];
    socket.dispatch("open");

    socket.dispatch("message", {
      data: JSON.stringify({
        channel: "broker-connection",
        brokerAccountId: "acc-1",
        eventType: "BROKER_CONNECTION_EVENT_TYPE_ESTABLISHED",
        detail: null,
      }),
    });

    await waitFor(() => expect(screen.getByText("Connected")).toBeInTheDocument());
  });

  it("ignores a message for a different brokerAccountId", async () => {
    render(
      <ConnectionStatusBadge
        brokerAccountId="acc-1"
        initialStatus="PENDING"
        accessToken="test-token"
      />,
    );
    const socket = FakeWebSocket.instances[0];
    socket.dispatch("open");
    socket.dispatch("message", {
      data: JSON.stringify({
        channel: "broker-connection",
        brokerAccountId: "acc-2",
        eventType: "BROKER_CONNECTION_EVENT_TYPE_ESTABLISHED",
      }),
    });

    // Give any (incorrect) update a tick to land, then assert it didn't.
    await new Promise((resolve) => setTimeout(resolve, 10));
    expect(screen.getByText("Pending")).toBeInTheDocument();
  });
});
