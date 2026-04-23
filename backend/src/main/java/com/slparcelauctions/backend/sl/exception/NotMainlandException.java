package com.slparcelauctions.backend.sl.exception;

/** Parcel's grid coordinates do not fall within any known Mainland continent. Maps to HTTP 422. */
public class NotMainlandException extends RuntimeException {

    private final double gridX;
    private final double gridY;

    public NotMainlandException(double gridX, double gridY) {
        super("Parcel is not on Mainland (grid " + gridX + ", " + gridY + ")");
        this.gridX = gridX;
        this.gridY = gridY;
    }

    public double getGridX() {
        return gridX;
    }

    public double getGridY() {
        return gridY;
    }
}
