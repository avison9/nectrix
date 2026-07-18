import { describe, expect, it, afterEach, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import type { CopyRelationship } from "@nectrix/api-client";
import { CopyRelationshipActions } from "./CopyRelationshipActions";
import {
  acknowledgeRiskAction,
  pauseCopyRelationshipAction,
  resumeCopyRelationshipAction,
  signAgreementAction,
  stopCopyRelationshipAction,
} from "./actions";

const refreshMock = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ refresh: refreshMock }),
}));

vi.mock("./actions", () => ({
  acknowledgeRiskAction: vi.fn(),
  signAgreementAction: vi.fn(),
  pauseCopyRelationshipAction: vi.fn(),
  resumeCopyRelationshipAction: vi.fn(),
  stopCopyRelationshipAction: vi.fn(),
}));

function relationship(status: CopyRelationship["status"]): CopyRelationship {
  return {
    id: "rel-1",
    masterProfileId: "master-1",
    followerBrokerAccountId: "account-1",
    status,
    moneyManagementProfile: {
      method: "MULTIPLIER",
      fixedLotSize: null,
      multiplier: 1.0,
      riskPercent: null,
      roundingMode: "DOWN",
    },
    riskProfile: {
      maxLotPerTrade: 5,
      maxOpenPositions: null,
      maxSlippagePips: 5,
      drawdownPausePct: 10,
      drawdownCloseAllPct: 20,
    },
    copyDirection: "COPY",
    feeCollectionMethod: "BROKER_PARTNERSHIP",
    originatingInvitationId: null,
    originatingFollowRequestId: null,
    highWaterMark: null,
    createdAt: "2026-01-01T00:00:00Z",
  };
}

afterEach(() => {
  cleanup();
  vi.mocked(acknowledgeRiskAction).mockReset();
  vi.mocked(signAgreementAction).mockReset();
  vi.mocked(pauseCopyRelationshipAction).mockReset();
  vi.mocked(resumeCopyRelationshipAction).mockReset();
  vi.mocked(stopCopyRelationshipAction).mockReset();
  refreshMock.mockReset();
});

describe("CopyRelationshipActions (TICKET-111 AC2/AC3 state-gated buttons)", () => {
  it("PENDING_RISK_ACK shows only the risk-acknowledgement action", () => {
    render(<CopyRelationshipActions relationship={relationship("PENDING_RISK_ACK")} />);
    expect(screen.getByText("I understand the risks")).toBeInTheDocument();
    expect(screen.queryByText("Sign agreement")).not.toBeInTheDocument();
    expect(screen.queryByText("Pause")).not.toBeInTheDocument();
    expect(screen.queryByText("Stop")).not.toBeInTheDocument();
  });

  it("PENDING_AGREEMENT shows only the sign-agreement action", () => {
    render(<CopyRelationshipActions relationship={relationship("PENDING_AGREEMENT")} />);
    expect(screen.getByText("Sign agreement")).toBeInTheDocument();
    expect(screen.queryByText("I understand the risks")).not.toBeInTheDocument();
    expect(screen.queryByText("Pause")).not.toBeInTheDocument();
  });

  it("ACTIVE shows Pause and Stop, not Resume", () => {
    render(<CopyRelationshipActions relationship={relationship("ACTIVE")} />);
    expect(screen.getByText("Pause")).toBeInTheDocument();
    expect(screen.getByText("Stop")).toBeInTheDocument();
    expect(screen.queryByText("Resume")).not.toBeInTheDocument();
  });

  it("PAUSED shows Resume and Stop, not Pause", () => {
    render(<CopyRelationshipActions relationship={relationship("PAUSED")} />);
    expect(screen.getByText("Resume")).toBeInTheDocument();
    expect(screen.getByText("Stop")).toBeInTheDocument();
    expect(screen.queryByText("Pause")).not.toBeInTheDocument();
  });

  it("STOPPED shows no actions, just an explanatory note", () => {
    render(<CopyRelationshipActions relationship={relationship("STOPPED")} />);
    expect(screen.queryByText("Pause")).not.toBeInTheDocument();
    expect(screen.queryByText("Resume")).not.toBeInTheDocument();
    expect(screen.queryByText("Stop")).not.toBeInTheDocument();
    expect(screen.getByText(/This relationship is stopped/)).toBeInTheDocument();
  });

  it("clicking Stop calls the server action and refreshes the router on success", async () => {
    vi.mocked(stopCopyRelationshipAction).mockResolvedValue(relationship("STOPPED"));
    render(<CopyRelationshipActions relationship={relationship("ACTIVE")} />);

    fireEvent.click(screen.getByText("Stop"));

    await waitFor(() => expect(stopCopyRelationshipAction).toHaveBeenCalledWith("rel-1"));
    await waitFor(() => expect(refreshMock).toHaveBeenCalled());
  });

  it("shows the server's error message and does not refresh when a transition is rejected (409)", async () => {
    vi.mocked(pauseCopyRelationshipAction).mockResolvedValue({
      error: "pause requires status=ACTIVE, but current status is PAUSED",
    });
    render(<CopyRelationshipActions relationship={relationship("ACTIVE")} />);

    fireEvent.click(screen.getByText("Pause"));

    await waitFor(() =>
      expect(
        screen.getByText("pause requires status=ACTIVE, but current status is PAUSED"),
      ).toBeInTheDocument(),
    );
    expect(refreshMock).not.toHaveBeenCalled();
  });
});
