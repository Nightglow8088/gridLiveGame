package com.example.pupupudemo.repository;

import com.example.pupupudemo.model.WorldResource;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface ResourceRepository extends MongoRepository<WorldResource, String> {
    // 这是一个非常重要的方法：根据坐标 (x, y) 查找这里有没有资源
    WorldResource findByXAndY(int x, int y);
}