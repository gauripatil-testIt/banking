package com.techverito.banking.config;

import com.techverito.banking.entity.RotationCursor;
import com.techverito.banking.repository.RotationCursorRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class RotationCursorInitializer implements ApplicationRunner {

    private final RotationCursorRepository rotationCursorRepository;

    public RotationCursorInitializer(RotationCursorRepository rotationCursorRepository) {
        this.rotationCursorRepository = rotationCursorRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (rotationCursorRepository.count() == 0) {
            rotationCursorRepository.save(new RotationCursor(RotationCursor.SINGLETON_ID, null));
        }
    }
}
