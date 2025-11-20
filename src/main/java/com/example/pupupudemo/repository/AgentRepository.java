package com.example.pupupudemo.repository;

import com.example.pupupudemo.model.Agent;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.List;

public interface AgentRepository extends MongoRepository<Agent, String> {
    // 我们可以加一个方法，方便查找所有活着的 Agent
    List<Agent> findByIsAliveTrue();
}