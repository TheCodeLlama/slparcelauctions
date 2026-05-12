import { describe, expect, it } from "vitest";
import { http, HttpResponse } from "msw";
import {
  renderWithProviders,
  screen,
  userEvent,
  waitFor,
} from "@/test/render";
import { server } from "@/test/msw/server";
import { realtyGroupReportHandlers } from "@/test/msw/handlers";
import { ReportGroupModal } from "./ReportGroupModal";

const GROUP_ID = "00000000-0000-0000-0000-0000000000aa";

const VALID_DETAILS =
  "Multiple shill bidders winning identical-looking parcels across this group.";

function renderModal(onClose: () => void = () => {}) {
  return renderWithProviders(
    <ReportGroupModal groupPublicId={GROUP_ID} open onClose={onClose} />,
    { auth: "authenticated" },
  );
}

describe("ReportGroupModal", () => {
  it("submitsHappyPath — POSTs reason + details, closes modal", async () => {
    let captured: { reason?: string; details?: string } = {};
    server.use(
      http.post(
        `*/api/v1/realty-groups/${GROUP_ID}/reports`,
        async ({ request }) => {
          captured = (await request.json()) as typeof captured;
          return HttpResponse.json(
            {
              publicId: "cccccccc-cccc-cccc-cccc-cccccccccccc",
              groupPublicId: GROUP_ID,
              reason: "FRAUDULENT_LISTINGS",
              status: "OPEN",
              createdAt: "2026-05-12T12:00:00Z",
            },
            { status: 201 },
          );
        },
      ),
    );
    const onClose = (() => {
      let count = 0;
      const fn: (() => void) & { callCount: () => number } = Object.assign(
        () => {
          count += 1;
        },
        { callCount: () => count },
      );
      return fn;
    })();

    renderModal(onClose);

    const select = screen.getByTestId("report-group-reason");
    await userEvent.selectOptions(select, "MISLEADING_ATTRIBUTION");
    await userEvent.type(
      screen.getByTestId("report-group-details"),
      VALID_DETAILS,
    );
    await userEvent.click(screen.getByTestId("report-group-submit"));

    await waitFor(() => expect(captured.reason).toBe("MISLEADING_ATTRIBUTION"));
    expect(captured.details).toBe(VALID_DETAILS);
    await waitFor(() => expect(onClose.callCount()).toBeGreaterThan(0));
  });

  it("validationError_detailsTooShort_blocksSubmit — surfaces inline error, no network call", async () => {
    let networkHit = false;
    server.use(
      http.post(`*/api/v1/realty-groups/${GROUP_ID}/reports`, () => {
        networkHit = true;
        return HttpResponse.json({}, { status: 201 });
      }),
    );
    renderModal();

    await userEvent.type(screen.getByTestId("report-group-details"), "too short");
    await userEvent.click(screen.getByTestId("report-group-submit"));

    expect(
      await screen.findByTestId("report-group-details-error"),
    ).toHaveTextContent(/at least 10 characters/i);
    expect(networkHit).toBe(false);
  });

  it("409Already_showsInlineError — surfaces ALREADY_REPORTED message", async () => {
    server.use(realtyGroupReportHandlers.submitAlreadyReported());
    renderModal();
    await userEvent.type(
      screen.getByTestId("report-group-details"),
      VALID_DETAILS,
    );
    await userEvent.click(screen.getByTestId("report-group-submit"));

    expect(
      await screen.findByTestId("report-group-inline-error"),
    ).toHaveTextContent(/already have an open report/i);
  });

  it("409CannotReportOwn_showsInlineError — surfaces CANNOT_REPORT_OWN_GROUP message", async () => {
    server.use(realtyGroupReportHandlers.submitOwnGroup());
    renderModal();
    await userEvent.type(
      screen.getByTestId("report-group-details"),
      VALID_DETAILS,
    );
    await userEvent.click(screen.getByTestId("report-group-submit"));

    expect(
      await screen.findByTestId("report-group-inline-error"),
    ).toHaveTextContent(/can't report a group you're a member of/i);
  });

  it("429RateLimit_showsInlineError — surfaces REPORT_RATE_LIMITED message", async () => {
    server.use(realtyGroupReportHandlers.submitRateLimited());
    renderModal();
    await userEvent.type(
      screen.getByTestId("report-group-details"),
      VALID_DETAILS,
    );
    await userEvent.click(screen.getByTestId("report-group-submit"));

    expect(
      await screen.findByTestId("report-group-inline-error"),
    ).toHaveTextContent(/daily report limit/i);
  });
});
