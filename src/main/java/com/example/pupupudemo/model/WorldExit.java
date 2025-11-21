package com.example.pupupudemo.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "world_exits")
public class WorldExit {
    @Id
    private String id;
    private int x;
    private int y;

    // 构造函数、Getter、Setter
    public WorldExit() {}
    public WorldExit(int x, int y) { this.x = x; this.y = y; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public int getX() { return x; }
    public void setX(int x) { this.x = x; }
    public int getY() { return y; }
    public void setY(int y) { this.y = y; }
}