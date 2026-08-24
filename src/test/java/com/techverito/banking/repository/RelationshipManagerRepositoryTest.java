package com.techverito.banking.repository;

import com.techverito.banking.entity.RelationshipManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class RelationshipManagerRepositoryTest {

    @Autowired
    RelationshipManagerRepository relationshipManagerRepository;

    @Test
    void findByActiveTrueOrderByIdAsc_returnsOnlyActiveSortedById() {
        RelationshipManager r1 = relationshipManagerRepository.save(
                RelationshipManager.builder().name("Alice").active(true).build());
        RelationshipManager r2 = relationshipManagerRepository.save(
                RelationshipManager.builder().name("Bob").active(false).build());
        RelationshipManager r3 = relationshipManagerRepository.save(
                RelationshipManager.builder().name("Carol").active(true).build());

        List<RelationshipManager> result = relationshipManagerRepository.findByActiveTrueOrderByIdAsc();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getId()).isEqualTo(r1.getId());
        assertThat(result.get(1).getId()).isEqualTo(r3.getId());
        assertThat(result).extracting(RelationshipManager::isActive).containsOnly(true);
        assertThat(result).noneMatch(rm -> rm.getId().equals(r2.getId()));
    }
}
