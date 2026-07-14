import { describe, expect, it, afterEach } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { DemoLiveTag } from "./DemoLiveTag";

afterEach(cleanup);

describe("DemoLiveTag (TICKET-110 AC5 — demo/live distinction)", () => {
  it("renders 'Demo' for a demo account", () => {
    render(<DemoLiveTag isDemo={true} />);
    expect(screen.getByText("Demo")).toBeInTheDocument();
  });

  it("renders 'Live' for a live account", () => {
    render(<DemoLiveTag isDemo={false} />);
    expect(screen.getByText("Live")).toBeInTheDocument();
  });
});
