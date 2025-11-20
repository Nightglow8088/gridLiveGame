package com.example.pupupudemo.model;

import com.fasterxml.jackson.annotation.JsonProperty; // 👈 导入这个
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.HashMap;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "agents")
public class Agent {

    @Id
    private String id;
    private String name;
    private int x;
    private int y;
    private int lifespan;

    // 👇 加上这个注解，强制 JSON 字段名为 "isAlive"
    @JsonProperty("isAlive")
    private boolean isAlive;

    @Builder.Default
    private Map<String, Integer> inventory = new HashMap<>();

    public void addResource(String type, int amount) {
        this.inventory.put(type, this.inventory.getOrDefault(type, 0) + amount);
    }
}