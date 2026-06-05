package com.wsb.dto;

import java.util.List;
import java.util.Map;

/**
 * 仿真状态快照，对应前端 STATE_UPDATE
 */
public class SimulationState {

    private String type = "STATE_UPDATE";
    private int tick;
    private boolean running;
    private Map<String, Object> config;
    private int[][] mapView;
    private int[][] mapBlock;
    private List<CarState> cars;

    public String getType() { return type; }
    public int getTick() { return tick; }
    public void setTick(int tick) { this.tick = tick; }
    public boolean isRunning() { return running; }
    public void setRunning(boolean running) { this.running = running; }
    public Map<String, Object> getConfig() { return config; }
    public void setConfig(Map<String, Object> config) { this.config = config; }
    public int[][] getMapView() { return mapView; }
    public void setMapView(int[][] mapView) { this.mapView = mapView; }
    public int[][] getMapBlock() { return mapBlock; }
    public void setMapBlock(int[][] mapBlock) { this.mapBlock = mapBlock; }
    public List<CarState> getCars() { return cars; }
    public void setCars(List<CarState> cars) { this.cars = cars; }
}
