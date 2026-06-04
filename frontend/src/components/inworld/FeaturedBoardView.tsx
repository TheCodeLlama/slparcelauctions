"use client";

import { apiUrl } from "@/lib/api/url";
import type { FeaturedBoardListing } from "@/types/promotion";

interface Props {
  listing: FeaturedBoardListing;
}

export function FeaturedBoardView({ listing }: Props) {
  const photo = apiUrl(listing.photoUrl) ?? undefined;
  const qrSrc = `https://api.qrserver.com/v1/create-qr-code/?size=80x80&data=${encodeURIComponent("https://slparcels.com" + listing.listingUrl)}`;

  return (
    <div
      style={{
        position: "relative",
        aspectRatio: "1 / 1",
        width: "100%",
        background: "#0d1b2a",
        color: "#f5f5f5",
        fontFamily: "-apple-system, 'Segoe UI', system-ui, sans-serif",
        overflow: "hidden",
      }}
    >
      {photo && (
        <img
          src={photo}
          alt=""
          style={{
            position: "absolute",
            inset: 0,
            width: "100%",
            height: "100%",
            objectFit: "cover",
          }}
        />
      )}

      {/* top bar */}
      <div
        style={{
          position: "absolute",
          top: 0,
          left: 0,
          right: 0,
          padding: "10px 14px",
          background: "linear-gradient(180deg, rgba(0,0,0,0.6), rgba(0,0,0,0))",
          display: "flex",
          justifyContent: "space-between",
          alignItems: "center",
          fontSize: 11,
          letterSpacing: 1.4,
          textTransform: "uppercase",
          color: "#b8d4f0",
          fontWeight: 600,
          zIndex: 3,
        }}
      >
        <span>SLPARCELS</span>
        <span
          style={{
            background: "#d97706",
            color: "#fff",
            padding: "3px 9px",
            borderRadius: 999,
            fontSize: 9,
            letterSpacing: 0.8,
          }}
        >
          FEATURED
        </span>
      </div>

      {/* gradient caption */}
      <div
        style={{
          position: "absolute",
          left: 0,
          right: 0,
          bottom: 0,
          padding: "14px 16px 16px",
          background:
            "linear-gradient(0deg, rgba(0,0,0,0.92) 0%, rgba(0,0,0,0.75) 40%, rgba(0,0,0,0.35) 80%, rgba(0,0,0,0) 100%)",
          display: "grid",
          gridTemplateColumns: "1fr auto",
          gap: 12,
          alignItems: "end",
        }}
      >
        <div>
          <div
            style={{
              fontSize: 18,
              fontWeight: 700,
              color: "#fff",
              lineHeight: 1.15,
              textShadow: "0 2px 6px rgba(0,0,0,0.9), 0 0 2px rgba(0,0,0,1)",
            }}
          >
            {listing.title}
          </div>
          <div
            style={{
              marginTop: 2,
              fontSize: 11,
              color: "#e5e5e5",
              textShadow: "0 1px 4px rgba(0,0,0,0.9)",
            }}
          >
            {listing.region}
            {listing.sqm ? ` ${"·"} ${listing.sqm} sqm` : ""}
          </div>
          <div style={{ marginTop: 6, display: "flex", alignItems: "baseline", gap: 10 }}>
            <span
              style={{
                fontSize: 22,
                fontWeight: 800,
                color: "#fbbf24",
                lineHeight: 1,
                textShadow: "0 2px 8px rgba(0,0,0,1), 0 0 4px rgba(0,0,0,1)",
              }}
            >
              L${listing.currentBid.toLocaleString()}
            </span>
            <span
              style={{
                fontSize: 12,
                color: "#fff",
                fontWeight: 600,
                textShadow: "0 1px 4px rgba(0,0,0,0.95)",
              }}
            >
              ends {new Date(listing.endsAt).toLocaleDateString()}
            </span>
          </div>
        </div>
        <img
          src={qrSrc}
          alt=""
          style={{
            width: 66,
            height: 66,
            background: "#fff",
            padding: 4,
            borderRadius: 3,
          }}
        />
      </div>
    </div>
  );
}
