package com.techverito.banking.service;

import com.techverito.banking.entity.RelationshipManager;
import com.techverito.banking.entity.RoundRobinState;
import com.techverito.banking.exception.NoActiveRelationshipManagerException;
import com.techverito.banking.repository.RelationshipManagerRepository;
import com.techverito.banking.repository.RoundRobinStateRepository;
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

    private RelationshipManager manager(Long id, boolean active) {
        return RelationshipManager.builder().id(id).name("RM" + id).active(active).build();
    }

    @Test
    void assignNext_noPriorPointer_assignsLowestIdActiveManager() {
        List<RelationshipManager> active = List.of(manager(1L, true), manager(2L, true));
        when(relationshipManagerRepository.findByActiveTrueOrderByIdAsc()).thenReturn(active);
        when(roundRobinStateRepository.findById(RoundRobinState.SINGLETON_ID)).thenReturn(Optional.empty());
        when(roundRobinStateRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RelationshipManager result = assignmentService.assignNext();

        assertThat(result.getId()).isEqualTo(1L);
        verify(roundRobinStateRepository).save(any());
    }

    @Test
    void assignNext_sequentialCalls_cycleThroughActiveManagersAndWrap() {
        List<RelationshipManager> active = List.of(manager(1L, true), manager(2L, true), manager(3L, true));
        when(relationshipManagerRepository.findByActiveTrueOrderByIdAsc()).thenReturn(active);

        RoundRobinState state = new RoundRobinState(RoundRobinState.SINGLETON_ID, null);
        when(roundRobinStateRepository.findById(RoundRobinState.SINGLETON_ID)).thenReturn(Optional.of(state));
        when(roundRobinStateRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RelationshipManager first = assignmentService.assignNext();
        assertThat(first.getId()).isEqualTo(1L);

        RelationshipManager second = assignmentService.assignNext();
        assertThat(second.getId()).isEqualTo(2L);

        RelationshipManager third = assignmentService.assignNext();
        assertThat(third.getId()).isEqualTo(3L);

        RelationshipManager fourth = assignmentService.assignNext();
        assertThat(fourth.getId()).isEqualTo(1L);
    }

    @Test
    void assignNext_inactiveManagers_areSkipped() {
        List<RelationshipManager> active = List.of(manager(1L, true), manager(3L, true));
        when(relationshipManagerRepository.findByActiveTrueOrderByIdAsc()).thenReturn(active);
        when(roundRobinStateRepository.findById(RoundRobinState.SINGLETON_ID)).thenReturn(Optional.empty());
        when(roundRobinStateRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RelationshipManager result = assignmentService.assignNext();

        assertThat(result.getId()).isEqualTo(1L);
    }

    @Test
    void assignNext_zeroActiveManagers_throwsException() {
        when(relationshipManagerRepository.findByActiveTrueOrderByIdAsc()).thenReturn(List.of());

        assertThatThrownBy(() -> assignmentService.assignNext())
                .isInstanceOf(NoActiveRelationshipManagerException.class);

        verify(roundRobinStateRepository, never()).findById(any());
    }
}
