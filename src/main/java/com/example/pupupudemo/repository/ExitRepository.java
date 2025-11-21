package com.example.pupupudemo.repository;

import com.example.pupupudemo.model.WorldExit;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface ExitRepository extends MongoRepository<WorldExit, String> {
}