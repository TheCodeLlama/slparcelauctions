"use client";

import { useEffect, useMemo, useState } from "react";
import { Canvas } from "@react-three/fiber";
import { Line, OrbitControls, PerspectiveCamera } from "@react-three/drei";

import { cn } from "@/lib/cn";
import { useParcelScan } from "@/hooks/useParcelScan";
import { useParcelMapColorMode } from "@/hooks/useParcelMapColorMode";
import { decodeBase64ToBytes } from "@/lib/parcelMap/encoding";
import {
  bicubicUpsample,
  buildHeightfieldGeometry,
  buildPerimeterPoints,
  computeCameraDefaults,
  computeParcelStats,
  computeRegionBounds,
  computeSlopeGrid,
  decodeElevationGrid,
  isWebGLAvailable,
} from "@/lib/parcelMap3D/geometry";
import { ParcelMap3DSkeleton } from "./ParcelMap3DSkeleton";
import { ParcelMap3DColorModeToggle } from "./ParcelMap3DColorModeToggle";
import { ParcelMap3DLegend } from "./ParcelMap3DLegend";

interface Props {
  publicId: string;
  /** Called once if WebGL is unavailable. Parent should switch to the 2D view. */
  onWebGLUnavailable?: () => void;
  className?: string;
}

/**
 * Interactive 3D view of the parcel + region heightmap. Drag to orbit,
 * scroll to zoom, middle-click to pan. The 64x64 sample grid is bicubic-
 * upsampled 2x and rendered as a continuous heightfield surface (no walls,
 * no staircase). The parcel is outlined in a white wireframe so it stays
 * visible from any angle. Outside-parcel vertices render in their full
 * gradient color; the wireframe is the parcel-vs-non-parcel signal in 3D.
 *
 * Spec: docs/superpowers/specs/2026-05-24-parcel-map-3d-design.md
 *
 * Default-exported so {@code next/dynamic} can lazy-import it cleanly:
 *   const ParcelMap3D = dynamic(() => import("./ParcelMap3D"), { ssr: false });
 */
export default function ParcelMap3D({
  publicId,
  onWebGLUnavailable,
  className,
}: Props) {
  const { data, isPending, isError } = useParcelScan(publicId);
  const webglOk = useMemo(() => isWebGLAvailable(), []);
  // Lazy initializer: safe because this component is only rendered client-side
  // (imported via next/dynamic({ ssr: false })). The spec opts out of
  // subscribing to mq.change events; a page reload reflects any OS preference
  // change.
  const [reducedMotion] = useState(
    () =>
      typeof window !== "undefined" &&
      window.matchMedia("(prefers-reduced-motion: reduce)").matches,
  );
  const [colorMode, setColorMode] = useParcelMapColorMode();

  useEffect(() => {
    if (!webglOk) onWebGLUnavailable?.();
  }, [webglOk, onWebGLUnavailable]);

  const decoded = useMemo(() => {
    if (!data) return null;
    return {
      layoutCells: decodeBase64ToBytes(data.layoutCellsBase64),
      heightCells: decodeBase64ToBytes(data.heightCellsBase64),
      baseMeters: data.baseMeters,
      stepMeters: data.stepMeters,
    };
  }, [data]);

  const rawGrid = useMemo(() => {
    if (!decoded) return null;
    return decodeElevationGrid(
      decoded.heightCells, decoded.baseMeters, decoded.stepMeters,
    );
  }, [decoded]);

  const upsampledGrid = useMemo(() => {
    if (!rawGrid) return null;
    return bicubicUpsample(rawGrid);
  }, [rawGrid]);

  const slopeGrid = useMemo(() => {
    if (!upsampledGrid) return null;
    return computeSlopeGrid(upsampledGrid);
  }, [upsampledGrid]);

  const stats = useMemo(() => {
    if (!decoded || !rawGrid) return null;
    return computeParcelStats(decoded.layoutCells, rawGrid);
  }, [decoded, rawGrid]);

  const bounds = useMemo(() => {
    if (!upsampledGrid) return null;
    return computeRegionBounds(upsampledGrid);
  }, [upsampledGrid]);

  const meshGeometry = useMemo(() => {
    if (!upsampledGrid || !stats || !bounds || !slopeGrid) return null;
    return buildHeightfieldGeometry(
      upsampledGrid,
      stats.parcelMin,
      bounds.rMax - stats.parcelMin,
      bounds.rMin - 8,
      colorMode,
      slopeGrid,
    );
  }, [upsampledGrid, stats, bounds, colorMode, slopeGrid]);

  // Dispose GPU-side BufferGeometry when it changes or the component unmounts
  // to prevent memory leaks.
  useEffect(() => {
    return () => {
      if (meshGeometry) meshGeometry.dispose();
    };
  }, [meshGeometry]);

  const perimeterPoints = useMemo(() => {
    if (!decoded || !upsampledGrid) return null;
    return buildPerimeterPoints(decoded.layoutCells, upsampledGrid);
  }, [decoded, upsampledGrid]);

  const camera = useMemo(() => {
    if (!bounds) return null;
    return computeCameraDefaults(bounds);
  }, [bounds]);

  if (!webglOk) {
    return (
      <p
        className={cn("text-xs text-fg-muted", className)}
        data-testid="parcel-map-3d-webgl-fallback"
      >
        3D view requires WebGL, which your browser does not support. Showing 2D
        view instead.
      </p>
    );
  }

  if (isPending) return <ParcelMap3DSkeleton className={className} />;
  if (
    isError || !data || !decoded || !stats || !bounds
    || !meshGeometry || !perimeterPoints || !camera || !slopeGrid
  ) {
    return null;
  }

  return (
    <div className={cn("flex flex-col items-center gap-2", className)}>
      <div
        role="img"
        aria-label="Interactive 3D region and parcel elevation map"
        className="relative aspect-square w-full max-w-[320px] bg-bg-subtle border border-border-subtle"
      >
        <Canvas>
          <PerspectiveCamera
            makeDefault
            fov={camera.fovDeg}
            position={camera.position}
            near={0.1}
            far={2000}
          />
          <OrbitControls
            target={camera.target}
            enableDamping={!reducedMotion}
            autoRotate={false}
            minDistance={20}
            maxDistance={1000}
          />
          <ambientLight intensity={0.4} />
          <directionalLight position={[-50, 100, -50]} intensity={1.0} />
          <mesh geometry={meshGeometry}>
            <meshStandardMaterial vertexColors />
          </mesh>
          {perimeterPoints.length > 0 && (
            <Line
              points={perimeterPoints}
              color="white"
              lineWidth={2}
              depthTest
              segments
            />
          )}
        </Canvas>
      </div>
      <div className="flex flex-col gap-1 w-full max-w-[320px]">
        <ParcelMap3DLegend
          mode={colorMode}
          maxDelta={bounds && stats ? bounds.rMax - stats.parcelMin : 0}
        />
        <ParcelMap3DColorModeToggle mode={colorMode} onChange={setColorMode} />
      </div>
    </div>
  );
}
