import { describe, expect, it, afterEach, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import type { BrokerAccountSummary } from "@nectrix/api-client";
import { CreateMasterProfileForm } from "./CreateMasterProfileForm";
import { createMasterProfileAction } from "./actions";

const pushMock = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: pushMock }),
}));

vi.mock("./actions", () => ({
  createMasterProfileAction: vi.fn(),
}));

const account: BrokerAccountSummary = {
  id: "account-1",
  userId: "user-1",
  brokerType: "CTRADER",
  brokerAccountLogin: "12345",
  displayLabel: "My cTrader",
  isDemo: false,
  currency: "USD",
  connectionRole: "MASTER_ONLY",
  openedViaIbLinkId: null,
  connectionStatus: "CONNECTED",
  lastHealthCheckAt: null,
  brokerName: null,
  serverName: null,
};

afterEach(() => {
  cleanup();
  vi.mocked(createMasterProfileAction).mockReset();
  pushMock.mockReset();
});

describe("CreateMasterProfileForm (TICKET-111)", () => {
  it("shows a link-a-broker-account prompt instead of the form when there are no broker accounts", () => {
    render(<CreateMasterProfileForm brokerAccounts={[]} />);
    expect(
      screen.getByText("Link a broker account before creating a Master profile."),
    ).toBeInTheDocument();
    expect(screen.queryByText("Create Master profile")).not.toBeInTheDocument();
  });

  it("on success, redirects to the new profile's own page", async () => {
    vi.mocked(createMasterProfileAction).mockResolvedValue({
      id: "new-profile-id",
      userId: "user-1",
      primaryBrokerAccountId: "account-1",
      displayName: "Test Master",
      bio: null,
      strategyTags: [],
      performanceFeePercent: 20,
      feeCollectionMethod: "BROKER_PARTNERSHIP",
      isPublic: true,
      verifiedAt: null,
      createdAt: "2026-01-01T00:00:00Z",
    });
    render(<CreateMasterProfileForm brokerAccounts={[account]} />);

    fireEvent.change(screen.getByLabelText(/Display name/i), { target: { value: "Test Master" } });
    fireEvent.click(screen.getByText("Create Master profile"));

    await waitFor(() => expect(pushMock).toHaveBeenCalledWith("/master-profile/new-profile-id"));
  });

  it("on 409 (already has one), redirects to the existing profile instead of showing an error", async () => {
    vi.mocked(createMasterProfileAction).mockResolvedValue({
      error: "You already have a Master profile.",
      existingProfileId: "existing-profile-id",
    });
    render(<CreateMasterProfileForm brokerAccounts={[account]} />);

    fireEvent.change(screen.getByLabelText(/Display name/i), { target: { value: "Test Master" } });
    fireEvent.click(screen.getByText("Create Master profile"));

    await waitFor(() =>
      expect(pushMock).toHaveBeenCalledWith("/master-profile/existing-profile-id"),
    );
    expect(screen.queryByText("You already have a Master profile.")).not.toBeInTheDocument();
  });

  it("on 403 (not a MASTER), shows the error inline and does not redirect", async () => {
    vi.mocked(createMasterProfileAction).mockResolvedValue({
      error: "Only accounts with the Master role can create a Master profile.",
    });
    render(<CreateMasterProfileForm brokerAccounts={[account]} />);

    fireEvent.change(screen.getByLabelText(/Display name/i), { target: { value: "Test Master" } });
    fireEvent.click(screen.getByText("Create Master profile"));

    await waitFor(() =>
      expect(
        screen.getByText("Only accounts with the Master role can create a Master profile."),
      ).toBeInTheDocument(),
    );
    expect(pushMock).not.toHaveBeenCalled();
  });
});
