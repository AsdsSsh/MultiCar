package inspection.common.model;

import com.alibaba.fastjson2.annotation.JSONField;

import java.util.Map;

/**
 * 仿真全局状态快照
 * 用于WebSocket推送给前端
 */
public class SimulationState {
    @JSONField(name = "mapWidth")
    private int mapWidth;
    
    @JSONField(name = "mapHeight")
    private int mapHeight;
    
    @JSONField(name = "mapView")
    private boolean[] mapView;
    
    @JSONField(name = "mapBlock")
    private boolean[] mapBlock;
    
    @JSONField(name = "exploredPercent")
    private double exploredPercent;
    
    @JSONField(name = "taskActive")
    private boolean taskActive;
    
    @JSONField(name = "tick")
    private long tick;
    
    @JSONField(name = "cars")
    private Map<String, CarInfo> cars;

    public SimulationState() {}

    public int getMapWidth() {
        return mapWidth;
    }

    public void setMapWidth(int mapWidth) {
        this.mapWidth = mapWidth;
    }

    public int getMapHeight() {
        return mapHeight;
    }

    public void setMapHeight(int mapHeight) {
        this.mapHeight = mapHeight;
    }

    public boolean[] getMapView() {
        return mapView;
    }

    public void setMapView(boolean[] mapView) {
        this.mapView = mapView;
    }

    public boolean[] getMapBlock() {
        return mapBlock;
    }

    public void setMapBlock(boolean[] mapBlock) {
        this.mapBlock = mapBlock;
    }

    public double getExploredPercent() {
        return exploredPercent;
    }

    public void setExploredPercent(double exploredPercent) {
        this.exploredPercent = exploredPercent;
    }

    public boolean isTaskActive() {
        return taskActive;
    }

    public void setTaskActive(boolean taskActive) {
        this.taskActive = taskActive;
    }

    public long getTick() {
        return tick;
    }

    public void setTick(long tick) {
        this.tick = tick;
    }

    public Map<String, CarInfo> getCars() {
        return cars;
    }

    public void setCars(Map<String, CarInfo> cars) {
        this.cars = cars;
    }
}
