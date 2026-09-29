package com.techverito.banking.service;

import com.techverito.banking.entity.Customer;
import com.techverito.banking.entity.ResetToken;
import com.techverito.banking.exception.InvalidOrExpiredTokenException;
import com.techverito.banking.repository.CustomerRepository;
import com.techverito.banking.repository.ResetTokenRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Optional;

@Service
@Transactional
public class PasswordResetService {

    private static final long TOKEN_VALIDITY_MINUTES = 30;

    private final CustomerRepository customerRepository;
    private final ResetTokenRepository resetTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final SecureRandom secureRandom = new SecureRandom();

    public PasswordResetService(CustomerRepository customerRepository,
                                 ResetTokenRepository resetTokenRepository,
                                 PasswordEncoder passwordEncoder,
                                 EmailService emailService) {
        this.customerRepository = customerRepository;
        this.resetTokenRepository = resetTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.emailService = emailService;
    }

    public void requestReset(String email) {
        Optional<Customer> customerOpt = customerRepository.findByEmail(email);
        if (customerOpt.isEmpty()) {
            return;
        }
        Customer customer = customerOpt.get();
        String token = generateToken();
        ResetToken resetToken = ResetToken.builder()
                .token(token)
                .customer(customer)
                .expiresAt(Instant.now().plus(TOKEN_VALIDITY_MINUTES, ChronoUnit.MINUTES))
                .build();
        resetTokenRepository.save(resetToken);
        emailService.sendResetEmail(customer.getEmail(), token);
    }

    public void confirmReset(String token, String newPassword) {
        ResetToken resetToken = resetTokenRepository.findByToken(token)
                .orElseThrow(() -> new InvalidOrExpiredTokenException("Invalid or expired token"));

        if (resetToken.getUsedAt() != null) {
            throw new InvalidOrExpiredTokenException("Invalid or expired token");
        }
        if (resetToken.getExpiresAt().isBefore(Instant.now())) {
            throw new InvalidOrExpiredTokenException("Invalid or expired token");
        }

        Customer customer = resetToken.getCustomer();
        customer.setPasswordHash(passwordEncoder.encode(newPassword));
        customerRepository.save(customer);

        resetToken.setUsedAt(Instant.now());
        resetTokenRepository.save(resetToken);

        emailService.sendPasswordChangedEmail(customer.getEmail());
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
