import { describe, expect, it, afterEach, beforeEach } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { RiskDisclosureBanner } from "./RiskDisclosureBanner";

const STORAGE_KEY = "nectrix_risk_disclosure_ack";

beforeEach(() => {
  localStorage.clear();
});

afterEach(() => {
  cleanup();
  localStorage.clear();
});

describe("RiskDisclosureBanner (TICKET-112 AC4 — non-dismissible risk disclosure)", () => {
  it("shows the overlay on first visit (no localStorage flag yet)", () => {
    render(<RiskDisclosureBanner />);
    expect(screen.getByText("I understand")).toBeInTheDocument();
  });

  it("clicking the acknowledge button hides the overlay and persists the flag", () => {
    render(<RiskDisclosureBanner />);
    fireEvent.click(screen.getByText("I understand"));

    expect(screen.queryByText("I understand")).not.toBeInTheDocument();
    expect(localStorage.getItem(STORAGE_KEY)).toBe("true");
  });

  it("does not render at all when the visitor already acknowledged in a prior visit", () => {
    localStorage.setItem(STORAGE_KEY, "true");
    render(<RiskDisclosureBanner />);
    expect(screen.queryByText("I understand")).not.toBeInTheDocument();
  });

  it("has no dismiss control other than the explicit acknowledge button (no close ×)", () => {
    render(<RiskDisclosureBanner />);
    const buttons = screen.getAllByRole("button");
    expect(buttons).toHaveLength(1);
    expect(buttons[0]).toHaveTextContent("I understand");
  });
});
