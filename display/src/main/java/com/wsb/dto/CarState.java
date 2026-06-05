package com.wsb.dto;

import java.util.List;

/**
 * 单辆小车完整状态，对应前端 CarStateDTO
 */
public class CarState {

    private String carId;
    private int x;
    private int y;
    private String status;        // IDLE / WAITING_ROUTE / READY / MOVING / BLOCKED
    private int targetX;
    private int targetY;
    private List<int[]> route;    // [[x,y], [x,y], ...]
    private int steps;

    public String getCarId() { return carId; }
    public void setCarId(String carId) { this.carId = carId; }
    public int getX() { return x; }
    public void setX(int x) { this.x = x; }
    public int getY() { return y; }
    public void setY(int y) { this.y = y; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getTargetX() { return targetX; }
    public void setTargetX(int targetX) { this.targetX = targetX; }
    public int getTargetY() { return targetY; }
    public void setTargetY(int targetY) { this.targetY = targetY; }
    public List<int[]> getRoute() { return route; }
    public void setRoute(List<int[]> route) { this.route = route; }
    public int getSteps() { return steps; }
    public void setSteps(int steps) { this.steps = steps; }
}
