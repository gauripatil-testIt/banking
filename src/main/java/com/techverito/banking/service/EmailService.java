package com.techverito.banking.service;

import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class EmailService {

    private final JavaMailSender mailSender;

    public EmailService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    public void sendResetEmail(String to, String token) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(to);
        message.setSubject("Password Reset Request");
        message.setText("A password reset was requested for your account. " +
                "Use the following token to reset your password: " + token +
                ". This token will expire in 30 minutes. " +
                "If you did not request this, please ignore this email.");
        mailSender.send(message);
    }

    public void sendPasswordChangedEmail(String to) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(to);
        message.setSubject("Your Password Has Been Changed");
        message.setText("This is a confirmation that the password for your account has been changed. " +
                "If you did not make this change, please contact support immediately.");
        mailSender.send(message);
    }
}
