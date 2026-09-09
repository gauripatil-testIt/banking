package com.techverito.banking.service;

import com.techverito.banking.entity.RelationshipManager;
import com.techverito.banking.entity.RelationshipManagerStatus;
import com.techverito.banking.entity.RoundRobinState;
import com.techverito.banking.exception.NoAvailableRelationshipManagerException;
import com.techverito.banking.repository.RelationshipManagerRepository;
import com.techverito.banking.repository.RoundRobinStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RelationshipManagerAssignmentServiceTest {

    @Mock
    RelationshipManagerRepository relationshipManagerRepository;

    @Mock
    RoundRobinStateRepository roundRobinStateRepository;

    @InjectMocks
    RelationshipManagerAssignmentService assignmentService;

    private RoundRobinState state;

    @BeforeEach
    void setUp() {
        state = new RoundRobinState();
        // entity fields are set in tests only after we see real names via compilation
    }

    @Test
    void assignNext_cyclesThroughActiveManagersInIdOrder_andAdvancesPointerOnlyAfterSuccessfulPick() {
        RelationshipManager rm1 = RelationshipManager.builder()
                .id(1L)
                .name("rm1")
                .status(RelationshipManagerStatus.ACTIVE)
                .maxCapacity(10L)
                .assignedCount(0L)
                .build();

        RelationshipManager rm2 = RelationshipManager.builder()
                .id(2L)
                .name("rm2")
                .status(RelationshipManagerStatus.ACTIVE)
                .maxCapacity(10L)
                .assignedCount(0L)
                .build();

        RelationshipManager rm3 = RelationshipManager.builder()
                .id(3L)
                .name("rm3")
                .status(RelationshipManagerStatus.ACTIVE)
                .maxCapacity(10L)
                .assignedCount(0L)
                .build();

        when(roundRobinStateRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(state));
        when(relationshipManagerRepository.findAllByStatusOrderByIdAsc(RelationshipManagerStatus.ACTIVE))
                .thenReturn(List.of(rm1, rm2, rm3));

        // simulate pointer starts "before" first eligible manager
        state.setLastAssignedManagerId(0L);

        when(relationshipManagerRepository.findByIdForCapacityUpdate(1L)).thenReturn(Optional.of(rm1));
        when(relationshipManagerRepository.findByIdForCapacityUpdate(2L)).thenReturn(Optional.of(rm2));
        when(relationshipManagerRepository.findByIdForCapacityUpdate(3L)).thenReturn(Optional.of(rm3));

        when(roundRobinStateRepository.save(any(RoundRobinState.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(relationshipManagerRepository.save(any(RelationshipManager.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Long first = assignmentService.assignNext();
        assertThat(first).isEqualTo(1L);
        assertThat(rm1.getAssignedCount()).isEqualTo(1L);

        Long second = assignmentService.assignNext();
        assertThat(second).isEqualTo(2L);
        assertThat(rm2.getAssignedCount()).isEqualTo(1L);

        Long third = assignmentService.assignNext();
        assertThat(third).isEqualTo(3L);
        assertThat(rm3.getAssignedCount()).isEqualTo(1L);

        verify(roundRobinStateRepository, atLeastOnce()).save(any(RoundRobinState.class));
    }

    @Test
    void assignNext_skipsOverCapacityManager_andPicksNextAvailable() {
        RelationshipManager rm1 = RelationshipManager.builder()
                .id(1L)
                .name("rm1")
                .status(RelationshipManagerStatus.ACTIVE)
                .maxCapacity(1L)
                .assignedCount(1L) // at capacity
                .build();

        RelationshipManager rm2 = RelationshipManager.builder()
                .id(2L)
                .name("rm2")
                .status(RelationshipManagerStatus.ACTIVE)
                .maxCapacity(10L)
                .assignedCount(0L)
                .build();

        when(roundRobinStateRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(state));
        when(relationshipManagerRepository.findAllByStatusOrderByIdAsc(RelationshipManagerStatus.ACTIVE))
                .thenReturn(List.of(rm1, rm2));

        state.setLastAssignedManagerId(0L);

        when(relationshipManagerRepository.findByIdForCapacityUpdate(1L)).thenReturn(Optional.of(rm1));
        when(relationshipManagerRepository.findByIdForCapacityUpdate(2L)).thenReturn(Optional.of(rm2));

        when(roundRobinStateRepository.save(any(RoundRobinState.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(relationshipManagerRepository.save(any(RelationshipManager.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Long chosen = assignmentService.assignNext();
        assertThat(chosen).isEqualTo(2L);
        assertThat(rm2.getAssignedCount()).isEqualTo(1L);

        // rm1 is full, should not be incremented
        assertThat(rm1.getAssignedCount()).isEqualTo(1L);
    }

    @Test
    void assignNext_whenNoActiveManagers_throwsNoAvailableRelationshipManagerException() {
        when(roundRobinStateRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(state));
        when(relationshipManagerRepository.findAllByStatusOrderByIdAsc(RelationshipManagerStatus.ACTIVE))
                .thenReturn(List.of());

        state.setLastAssignedManagerId(0L);

        assertThatThrownBy(() -> assignmentService.assignNext())
                .isInstanceOf(NoAvailableRelationshipManagerException.class);
    }

    @Test
    void assignNext_whenAllActiveManagersOverCapacity_throwsNoAvailableRelationshipManagerException() {
        RelationshipManager rm1 = RelationshipManager.builder()
                .id(1L)
                .name("rm1")
                .status(RelationshipManagerStatus.ACTIVE)
                .maxCapacity(1L)
                .assignedCount(1L)
                .build();

        when(roundRobinStateRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(state));
        when(relationshipManagerRepository.findAllByStatusOrderByIdAsc(RelationshipManagerStatus.ACTIVE))
                .thenReturn(List.of(rm1));

        state.setLastAssignedManagerId(0L);
        when(relationshipManagerRepository.findByIdForCapacityUpdate(1L)).thenReturn(Optional.of(rm1));

        assertThatThrownBy(() -> assignmentService.assignNext())
                .isInstanceOf(NoAvailableRelationshipManagerException.class);
    }
}
