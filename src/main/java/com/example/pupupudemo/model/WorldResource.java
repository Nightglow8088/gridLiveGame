package com.example.pupupudemo.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "world_resources")
public class WorldResource {

    @Id
    private String id;
    private String type; // "Wheat", "Stone"
    private int x;
    private int y;
}