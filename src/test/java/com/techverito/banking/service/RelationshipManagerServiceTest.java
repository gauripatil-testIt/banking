package com.techverito.banking.service;

import com.techverito.banking.dto.RelationshipManagerRequest;
import com.techverito.banking.dto.RelationshipManagerResponse;
import com.techverito.banking.entity.RelationshipManager;
import com.techverito.banking.entity.RotationCursor;
import com.techverito.banking.exception.BadRequestException;
import com.techverito.banking.exception.ResourceNotFoundException;
import com.techverito.banking.repository.RelationshipManagerRepository;
import com.techverito.banking.repository.RotationCursorRepository;
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
class RelationshipManagerServiceTest {

    @Mock
    RelationshipManagerRepository relationshipManagerRepository;

    @Mock
    RotationCursorRepository rotationCursorRepository;

    @InjectMocks
    RelationshipManagerService relationshipManagerService;

    private RelationshipManagerRequest request() {
        return new RelationshipManagerRequest("Alice", "alice@example.com");
    }

    private RelationshipManager manager(Long id) {
        return RelationshipManager.builder()
                .id(id).name("Alice").email("alice@example.com").active(true).build();
    }

    private RelationshipManager manager(Long id, String name, boolean active) {
        return RelationshipManager.builder()
                .id(id).name(name).email(name.toLowerCase() + "@example.com").active(active).build();
    }

    @Test
    void create_newEmail_savesAndReturnsActiveResponse() {
        when(relationshipManagerRepository.findByEmail("alice@example.com")).thenReturn(Optional.empty());
        when(relationshipManagerRepository.save(any())).thenReturn(manager(1L));

        RelationshipManagerResponse res = relationshipManagerService.create(request());

        assertThat(res.id()).isEqualTo(1L);
        assertThat(res.name()).isEqualTo("Alice");
        assertThat(res.email()).isEqualTo("alice@example.com");
        assertThat(res.active()).isTrue();
        verify(relationshipManagerRepository).save(any(RelationshipManager.class));
    }

    @Test
    void create_duplicateEmail_throwsBadRequestAndDoesNotSave() {
        when(relationshipManagerRepository.findByEmail("alice@example.com"))
                .thenReturn(Optional.of(manager(1L)));

        assertThatThrownBy(() -> relationshipManagerService.create(request()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("alice@example.com");

        verify(relationshipManagerRepository, never()).save(any());
    }

    @Test
    void updateStatus_found_updatesActiveFlag() {
        RelationshipManager existing = manager(1L);
        when(relationshipManagerRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(relationshipManagerRepository.save(any())).thenReturn(existing);

        RelationshipManagerResponse res = relationshipManagerService.updateStatus(1L, false);

        assertThat(res.active()).isFalse();
        verify(relationshipManagerRepository).save(existing);
    }

    @Test
    void updateStatus_notFound_throwsException() {
        when(relationshipManagerRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> relationshipManagerService.updateStatus(99L, false))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void assignNextManager_cyclesThroughActiveManagersInOrderAndWraps() {
        RelationshipManager m1 = manager(1L, "Alice", true);
        RelationshipManager m2 = manager(2L, "Bob", true);
        RelationshipManager m3 = manager(3L, "Carol", true);
        List<RelationshipManager> managers = List.of(m1, m2, m3);

        RotationCursor cursor = new RotationCursor(RotationCursor.SINGLETON_ID, null);
        when(rotationCursorRepository.lockForUpdate(RotationCursor.SINGLETON_ID)).thenReturn(Optional.of(cursor));
        when(relationshipManagerRepository.findAllByOrderByIdAsc()).thenReturn(managers);
        when(rotationCursorRepository.save(any())).thenReturn(cursor);

        assertThat(relationshipManagerService.assignNextManager().getId()).isEqualTo(1L);
        cursor.setLastAssignedManagerId(1L);

        assertThat(relationshipManagerService.assignNextManager().getId()).isEqualTo(2L);
        cursor.setLastAssignedManagerId(2L);

        assertThat(relationshipManagerService.assignNextManager().getId()).isEqualTo(3L);
        cursor.setLastAssignedManagerId(3L);

        assertThat(relationshipManagerService.assignNextManager().getId()).isEqualTo(1L);
    }

    @Test
    void assignNextManager_skipsInactiveManagers() {
        RelationshipManager m1 = manager(1L, "Alice", true);
        RelationshipManager m2 = manager(2L, "Bob", false);
        RelationshipManager m3 = manager(3L, "Carol", true);
        List<RelationshipManager> managers = List.of(m1, m2, m3);

        RotationCursor cursor = new RotationCursor(RotationCursor.SINGLETON_ID, 1L);
        when(rotationCursorRepository.lockForUpdate(RotationCursor.SINGLETON_ID)).thenReturn(Optional.of(cursor));
        when(relationshipManagerRepository.findAllByOrderByIdAsc()).thenReturn(managers);
        when(rotationCursorRepository.save(any())).thenReturn(cursor);

        RelationshipManager assigned = relationshipManagerService.assignNextManager();

        assertThat(assigned.getId()).isEqualTo(3L);
    }

    @Test
    void assignNextManager_noActiveManagers_throwsBadRequest() {
        RelationshipManager m1 = manager(1L, "Alice", false);
        List<RelationshipManager> managers = List.of(m1);

        RotationCursor cursor = new RotationCursor(RotationCursor.SINGLETON_ID, null);
        when(rotationCursorRepository.lockForUpdate(RotationCursor.SINGLETON_ID)).thenReturn(Optional.of(cursor));
        when(relationshipManagerRepository.findAllByOrderByIdAsc()).thenReturn(managers);

        assertThatThrownBy(() -> relationshipManagerService.assignNextManager())
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("No active relationship managers available");
    }
}
