export function PlaceholderBoardView() {
  return (
    <div
      style={{
        position: "relative",
        aspectRatio: "1 / 1",
        width: "100%",
        background: "linear-gradient(135deg, #0d1b2a, #1a3050)",
        color: "#fff",
        fontFamily: "-apple-system, 'Segoe UI', system-ui, sans-serif",
        display: "flex",
        flexDirection: "column",
        justifyContent: "center",
        alignItems: "center",
        textAlign: "center",
        padding: 24,
      }}
    >
      <div
        style={{
          fontSize: 13,
          letterSpacing: 1.5,
          textTransform: "uppercase",
          color: "#b8d4f0",
          fontWeight: 600,
        }}
      >
        SLPARCELS
      </div>
      <div style={{ fontSize: 22, fontWeight: 700, marginTop: 16, lineHeight: 1.2 }}>
        List your parcel here
      </div>
      <div style={{ fontSize: 13, color: "#b8d4f0", marginTop: 12 }}>
        slparcels.com
      </div>
    </div>
  );
}
