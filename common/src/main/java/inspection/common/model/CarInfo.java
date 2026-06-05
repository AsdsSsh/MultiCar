package inspection.common.model;

import com.alibaba.fastjson2.annotation.JSONField;

import java.util.List;

/**
 * 小车信息（用于前端显示）
 */
public class CarInfo {
    @JSONField(name = "carId")
    private String carId;
    
    @JSONField(name = "displayId")
    private String displayId;
    
    @JSONField(name = "position")
    private Point position;
    
    @JSONField(name = "target")
    private Point target;
    
    @JSONField(name = "routeList")
    private List<Point> routeList;
    
    @JSONField(name = "status")
    private String status;
    
    @JSONField(name = "stepsWalked")
    private int stepsWalked;
    
    @JSONField(name = "statusColor")
    private String statusColor;

    public CarInfo() {}

    public String getCarId() {
        return carId;
    }

    public void setCarId(String carId) {
        this.carId = carId;
        if (carId != null && carId.startsWith("Car")) {
            this.displayId = carId.substring(3);
        }
    }

    public String getDisplayId() {
        return displayId;
    }

    public void setDisplayId(String displayId) {
        this.displayId = displayId;
    }

    public Point getPosition() {
        return position;
    }

    public void setPosition(Point position) {
        this.position = position;
    }

    public Point getTarget() {
        return target;
    }

    public void setTarget(Point target) {
        this.target = target;
    }

    public List<Point> getRouteList() {
        return routeList;
    }

    public void setRouteList(List<Point> routeList) {
        this.routeList = routeList;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
        CarStatus cs = CarStatus.fromString(status);
        this.statusColor = cs != null ? cs.getColor() : "#9E9E9E";
    }

    public int getStepsWalked() {
        return stepsWalked;
    }

    public void setStepsWalked(int stepsWalked) {
        this.stepsWalked = stepsWalked;
    }

    public String getStatusColor() {
        return statusColor;
    }

    public void setStatusColor(String statusColor) {
        this.statusColor = statusColor;
    }
}
