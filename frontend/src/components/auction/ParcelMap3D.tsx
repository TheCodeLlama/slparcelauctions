"use client";

import { useEffect, useMemo, useState } from "react";
import { Canvas } from "@react-three/fiber";
import { Line, OrbitControls, PerspectiveCamera } from "@react-three/drei";

import { cn } from "@/lib/cn";
import { useParcelScan } from "@/hooks/useParcelScan";
import { decodeBase64ToBytes } from "@/lib/parcelMap/encoding";
import {
  bicubicUpsample,
  buildHeightfieldGeometry,
  buildPerimeterPoints,
  computeCameraDefaults,
  computeParcelStats,
  computeRegionBounds,
  decodeElevationGrid,
  isWebGLAvailable,
} from "@/lib/parcelMap3D/geometry";
import { ParcelMap3DSkeleton } from "./ParcelMap3DSkeleton";

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

  const stats = useMemo(() => {
    if (!decoded || !rawGrid) return null;
    return computeParcelStats(decoded.layoutCells, rawGrid);
  }, [decoded, rawGrid]);

  const bounds = useMemo(() => {
    if (!upsampledGrid) return null;
    return computeRegionBounds(upsampledGrid);
  }, [upsampledGrid]);

  const meshGeometry = useMemo(() => {
    if (!upsampledGrid || !stats) return null;
    return buildHeightfieldGeometry(upsampledGrid, stats.parcelMin);
  }, [upsampledGrid, stats]);

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
    || !meshGeometry || !perimeterPoints || !camera
  ) {
    return null;
  }

  return (
    <div
      role="img"
      aria-label="Interactive 3D region and parcel elevation map"
      className={cn(
        "aspect-square w-full max-w-[320px] bg-bg-subtle border border-border-subtle",
        className,
      )}
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
  );
}
