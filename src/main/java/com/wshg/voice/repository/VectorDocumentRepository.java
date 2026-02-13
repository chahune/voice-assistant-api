package com.wshg.voice.repository;

import com.wshg.voice.entity.VectorDocumentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VectorDocumentRepository extends JpaRepository<VectorDocumentEntity, String> {

    List<VectorDocumentEntity> findBySource(String source);

    void deleteBySource(String source);
}
