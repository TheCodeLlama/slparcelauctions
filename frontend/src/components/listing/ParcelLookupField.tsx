"use client";

import { useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { Button } from "@/components/ui/Button";
import { Input } from "@/components/ui/Input";
import { ApiError } from "@/lib/api";
import { lookupParcel } from "@/lib/api/parcels";
import type { ParcelDto } from "@/types/parcel";
import { ParcelLookupCard } from "./ParcelLookupCard";

const UUID_REGEX =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export interface ParcelLookupFieldProps {
  initialParcel?: ParcelDto | null;
  /** When true, the UUID is shown but not re-lookup-able — used on Edit. */
  locked?: boolean;
  onResolved: (parcel: ParcelDto) => void;
}

/**
 * UUID entry + lookup button pair. Client-side guards the UUID shape
 * before hitting the backend so malformed input never reaches the server
 * rate-limited lookup endpoint.
 *
 * Maps backend error statuses to friendly copy rather than leaking
 * ProblemDetail titles. The failure cases come from ParcelLookupService
 * (400 malformed, 404 not found, 422 non-Mainland, 504 upstream timeout).
 */
export function ParcelLookupField({
  initialParcel = null,
  locked = false,
  onResolved,
}: ParcelLookupFieldProps) {
  const [uuid, setUuid] = useState(initialParcel?.slParcelUuid ?? "");
  const [parcel, setParcel] = useState<ParcelDto | null>(initialParcel);
  const [clientError, setClientError] = useState<string | null>(null);
  const [serverError, setServerError] = useState<string | null>(null);

  const mutation = useMutation({
    mutationFn: (id: string) => lookupParcel(id),
    onSuccess: (data) => {
      setParcel(data);
      setServerError(null);
      onResolved(data);
    },
    onError: (e) => {
      if (e instanceof ApiError) {
        const statusCopy: Record<number, string> = {
          400:
            "This doesn't look like a valid Second Life parcel UUID. Double-check and try again.",
          404:
            "We couldn't find this parcel in Second Life. Check the UUID or try again later.",
          422:
            "This parcel isn't on a Mainland continent. Phase 1 supports Mainland parcels only.",
          504:
            "Second Life's parcel service is slow or down right now. Try again in a moment.",
        };
        setServerError(
          statusCopy[e.status] ??
            e.problem.detail ??
            e.problem.title ??
            "Lookup failed.",
        );
      } else {
        setServerError("Lookup failed. Try again.");
      }
    },
  });

  function submit() {
    const trimmed = uuid.trim();
    if (!UUID_REGEX.test(trimmed)) {
      setClientError(
        "Enter a valid UUID like 00000000-0000-0000-0000-000000000000.",
      );
      return;
    }
    setClientError(null);
    setServerError(null);
    mutation.mutate(trimmed);
  }

  return (
    <div className="flex flex-col gap-3">
      <div className="flex items-end gap-3">
        <div className="flex-1">
          <Input
            label="Parcel UUID"
            value={uuid}
            onChange={(e) => setUuid(e.target.value)}
            placeholder="00000000-0000-0000-0000-000000000000"
            disabled={locked || mutation.isPending}
            error={clientError ?? serverError ?? undefined}
            autoComplete="off"
            spellCheck={false}
          />
        </div>
        {!locked && (
          <Button
            type="button"
            variant="secondary"
            onClick={submit}
            loading={mutation.isPending}
            disabled={mutation.isPending}
          >
            Look up
          </Button>
        )}
      </div>
      {parcel && <ParcelLookupCard parcel={parcel} />}
    </div>
  );
}
